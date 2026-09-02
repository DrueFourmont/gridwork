package com.dfsystems.gridwork.core.service

import com.dfsystems.gridwork.core.persistence.CellRepository
import com.dfsystems.gridwork.core.persistence.ColumnRepository
import com.dfsystems.gridwork.core.persistence.StoredCell
import com.dfsystems.gridwork.core.realtime.CellsChangedEvent
import com.dfsystems.gridwork.core.realtime.Outbound
import com.dfsystems.gridwork.core.error.CellConflictException
import com.dfsystems.gridwork.core.error.NotFoundException
import com.dfsystems.gridwork.core.error.FieldError
import com.dfsystems.gridwork.core.error.UnprocessableException
import com.dfsystems.gridwork.domain.BatchOutcome
import com.dfsystems.gridwork.domain.BatchUpdateRule
import com.dfsystems.gridwork.domain.CellAddress
import com.dfsystems.gridwork.domain.CellConflict
import com.dfsystems.gridwork.domain.CellParse
import com.dfsystems.gridwork.domain.CellWrite
import com.dfsystems.gridwork.domain.ColumnId
import com.dfsystems.gridwork.domain.RowId
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.UserId
import com.dfsystems.gridwork.domain.Version
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The batch cell update, which is the endpoint ADR 0001 exists for.
 *
 * The whole batch is one transaction. Any conflict aborts all of it and the
 * caller gets a 409 listing every conflicting cell with its current value, so a
 * UI can show a merge without a refetch.
 *
 * There are two version checks here, and both are needed. The first is in the
 * domain rule against versions we just read, which produces a good error
 * message. The second is the `where version = :expected` in the UPDATE itself,
 * which is the one that is actually atomic. Between the read and the write
 * another transaction can commit; only the second check catches that, and it
 * catches it by affecting zero rows.
 */
@Service
class CellService(
    private val cells: CellRepository,
    private val columns: ColumnRepository,
    private val access: AccessService,
    private val events: org.springframework.context.ApplicationEventPublisher,
) {

    data class RequestedWrite(
        val rowId: UUID,
        val columnId: UUID,
        val value: String?,
        val expectedVersion: Long,
    )

    data class AppliedCell(val address: CellAddress, val value: String?, val version: Version)

    @Transactional
    fun batchUpdate(
        sheetId: SheetId,
        actorId: UserId,
        requested: List<RequestedWrite>,
    ): List<AppliedCell> {
        access.requireWriteCells(sheetId, actorId)

        if (requested.size > MAX_BATCH) {
            throw UnprocessableException("A batch may contain at most $MAX_BATCH cells.")
        }

        val columnTypes = columns.findBySheetIdOrderByPositionAsc(sheetId.value)
            .associate { ColumnId(it.id) to it.type }

        // Validate every value against its column's type before touching the
        // database, and report all the bad ones at once. Failing on the first
        // one makes a client fix a fifty cell paste one cell per round trip.
        val problems = mutableListOf<FieldError>()
        val writes = requested.mapIndexedNotNull { index, item ->
            val address = CellAddress(RowId(item.rowId), ColumnId(item.columnId))
            val type = columnTypes[address.columnId]
            if (type == null) {
                problems += FieldError("updates[$index].columnId", "is not a column of this sheet")
                return@mapIndexedNotNull null
            }
            if (item.expectedVersion < 1) {
                problems += FieldError("updates[$index].expectedVersion", "must be 1 or greater")
                return@mapIndexedNotNull null
            }
            when (val parsed = type.parse(item.value)) {
                is CellParse.Invalid -> {
                    problems += FieldError(
                        "updates[$index].value",
                        "is not valid for a $type column (${parsed.problem})",
                    )
                    null
                }
                is CellParse.Valid -> CellWrite(address, parsed.value, Version(item.expectedVersion))
            }
        }
        if (problems.isNotEmpty()) {
            throw UnprocessableException("One or more cell values are not valid.", problems)
        }

        val current = cells
            .findByAddresses(sheetId, writes.map { it.address })
            .associateBy { it.address }

        when (val outcome = BatchUpdateRule.evaluate(writes, current.mapValues { it.value.version })) {
            is BatchOutcome.EmptyBatch ->
                throw UnprocessableException("A batch must contain at least one cell update.")

            is BatchOutcome.DuplicateCells ->
                throw UnprocessableException(
                    "The same cell appears more than once in this batch.",
                    outcome.addresses.map {
                        FieldError("updates", "duplicate cell ${it.rowId}/${it.columnId}")
                    },
                )

            is BatchOutcome.UnknownCells ->
                throw NotFoundException(
                    "These cells do not exist in this sheet: " +
                        outcome.addresses.joinToString { "${it.rowId}/${it.columnId}" },
                )

            is BatchOutcome.Conflicted -> throw conflict(outcome.conflicts, current)

            is BatchOutcome.Applicable -> return apply(sheetId, actorId, outcome.writes, current)
        }
    }

    private fun apply(
        sheetId: SheetId,
        actorId: UserId,
        writes: List<CellWrite>,
        current: Map<CellAddress, StoredCell>,
    ): List<AppliedCell> {
        val affected = cells.applyBatch(sheetId, writes, actorId)

        // A zero here means the version changed between the read above and this
        // statement. The rule check passed and the database still refused, which
        // is the whole point of putting the version in the WHERE clause.
        val lost = writes.filterIndexed { index, _ -> affected.getOrElse(index) { 0 } == 0 }
        if (lost.isNotEmpty()) {
            val fresh = cells.findByAddresses(sheetId, lost.map { it.address }).associateBy { it.address }
            throw conflict(
                lost.map { write ->
                    CellConflict(
                        address = write.address,
                        expected = write.expectedVersion,
                        // If the cell vanished entirely, report the version the
                        // caller expected rather than inventing one.
                        actual = fresh[write.address]?.version ?: write.expectedVersion,
                    )
                },
                fresh,
            )
        }

        val applied = writes.map { write ->
            AppliedCell(
                address = write.address,
                value = write.value.asStored(),
                version = write.expectedVersion.next(),
            )
        }
        // Same transaction as the write, so history cannot drift from the data.
        // It doubles as the replay log the websocket protocol reads, see
        // CellHistoryRepository.
        val sequences = cells.recordHistory(
            sheetId = sheetId,
            entries = writes.map { write ->
                CellRepository.HistoryEntry(
                    address = write.address,
                    oldValue = current[write.address]?.value,
                    newValue = write.value.asStored(),
                    newVersion = write.expectedVersion.next(),
                )
            },
            changedBy = actorId,
        )

        // Raised, not published. A TransactionalEventListener holds it until
        // this transaction commits, so no other replica can be told about a
        // change it would not yet be able to read. See ADR 0007.
        events.publishEvent(
            CellsChangedEvent(
                applied.mapIndexed { index, cell ->
                    Outbound.CellChanged(
                        sheetId = sheetId.toString(),
                        rowId = cell.address.rowId.toString(),
                        columnId = cell.address.columnId.toString(),
                        value = cell.value,
                        version = cell.version.value,
                        sequence = sequences.getOrElse(index) { 0L },
                        changedBy = actorId.toString(),
                    )
                },
            ),
        )
        return applied
    }

    private fun conflict(
        conflicts: List<CellConflict>,
        current: Map<CellAddress, StoredCell>,
    ) = CellConflictException(
        conflicts = conflicts,
        currentValues = conflicts.associate { it.address to current[it.address]?.value },
    )

    companion object {
        /**
         * Bounded so one request cannot lock every cell in a sheet. Also the
         * number the load test in the budget table drives.
         */
        const val MAX_BATCH = 500
    }
}
