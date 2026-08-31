package com.dfsystems.gridwork.api.service

import com.dfsystems.gridwork.api.persistence.CellRepository
import com.dfsystems.gridwork.api.persistence.ColumnRepository
import com.dfsystems.gridwork.api.persistence.RowEntity
import com.dfsystems.gridwork.api.persistence.RowRepository
import com.dfsystems.gridwork.api.persistence.StoredCell
import com.dfsystems.gridwork.api.web.UnprocessableException
import com.dfsystems.gridwork.domain.ColumnId
import com.dfsystems.gridwork.domain.RowId
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.UserId
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class RowService(
    private val rows: RowRepository,
    private val columns: ColumnRepository,
    private val cells: CellRepository,
    private val access: AccessService,
) {

    @Transactional
    fun append(sheetId: SheetId, actorId: UserId): RowEntity {
        access.requireStructure(sheetId, actorId)
        if (rows.countBySheetId(sheetId.value) >= MAX_ROWS) {
            throw UnprocessableException("A sheet may have at most $MAX_ROWS rows.")
        }
        // Positions are append only in this phase. Reordering means rewriting
        // the positions of everything after the moved row, which is its own
        // concurrency problem and belongs with the grid in Phase 2.
        // saveAndFlush, not save. The cell inserts below go through
        // JdbcTemplate, which does not see JPA's persistence context and does
        // not trigger a flush. Without the flush the row exists only in memory
        // and the cell insert fails on cells_row_id_fkey. This is the seam
        // between the ORM and the plain SQL, and it has to be crossed on
        // purpose rather than by accident.
        val row = rows.saveAndFlush(
            RowEntity(
                id = UUID.randomUUID(),
                sheetId = sheetId.value,
                position = rows.maxPosition(sheetId.value) + 1,
                createdAt = Instant.now(),
            ),
        )
        cells.createEmptyCellsForRow(
            sheetId = sheetId,
            rowId = RowId(row.id),
            columnIds = columns.findBySheetIdOrderByPositionAsc(sheetId.value).map { ColumnId(it.id) },
            createdBy = actorId,
        )
        return row
    }

    /**
     * One page of rows with their cells.
     *
     * Two queries, not one per row. The rows come back in a keyset range scan,
     * then every cell for those rows is fetched in a single statement and
     * grouped in memory. The alternative, a query per row, is the N+1 that
     * makes a grid feel slow at exactly the point it starts being useful.
     */
    @Transactional(readOnly = true)
    fun page(sheetId: SheetId, actorId: UserId, cursorPosition: Long?, limit: Int): RowPage {
        access.requireRead(sheetId, actorId)
        val window = Limit.of(limit + 1)
        val fetched = if (cursorPosition == null) {
            rows.firstPageForSheet(sheetId.value, window)
        } else {
            rows.nextPageForSheet(sheetId.value, cursorPosition, window)
        }
        val hasMore = fetched.size > limit
        val page = if (hasMore) fetched.take(limit) else fetched
        val cellsByRow = cells
            .findBySheetAndRows(sheetId, page.map { RowId(it.id) })
            .groupBy { it.address.rowId }
        return RowPage(
            rows = page,
            cellsByRow = cellsByRow,
            nextCursorPosition = if (hasMore) page.lastOrNull()?.position else null,
        )
    }

    data class RowPage(
        val rows: List<RowEntity>,
        val cellsByRow: Map<RowId, List<StoredCell>>,
        val nextCursorPosition: Long?,
    )

    companion object {
        const val MAX_ROWS = 50_000
    }
}
