package com.dfsystems.gridwork.core.service

import com.dfsystems.gridwork.core.persistence.ColumnEntity
import com.dfsystems.gridwork.core.persistence.ColumnRepository
import com.dfsystems.gridwork.core.persistence.CellRepository
import com.dfsystems.gridwork.core.persistence.RowRepository
import com.dfsystems.gridwork.core.persistence.SheetEntity
import com.dfsystems.gridwork.core.persistence.SheetMemberEntity
import com.dfsystems.gridwork.core.persistence.SheetMemberRepository
import com.dfsystems.gridwork.core.persistence.SheetRepository
import com.dfsystems.gridwork.core.persistence.UserRepository
import com.dfsystems.gridwork.core.error.NotFoundException
import com.dfsystems.gridwork.core.error.UnprocessableException
import com.dfsystems.gridwork.domain.ColumnId
import com.dfsystems.gridwork.domain.ColumnType
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.SheetRole
import com.dfsystems.gridwork.domain.UserId
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Sheets, their members, and their columns.
 *
 * Transactions live here, not on the controllers, per CLAUDE.md. Every method
 * that touches more than one table is one transaction, so a half created sheet
 * with no owner membership is not a state the database can be left in.
 */
@Service
class SheetService(
    private val sheets: SheetRepository,
    private val members: SheetMemberRepository,
    private val columns: ColumnRepository,
    private val rows: RowRepository,
    private val cells: CellRepository,
    private val users: UserRepository,
    private val access: AccessService,
) {

    @Transactional
    fun create(name: String, ownerId: UserId): SheetEntity {
        val now = Instant.now()
        val sheet = sheets.save(
            SheetEntity(
                id = UUID.randomUUID(),
                ownerId = ownerId.value,
                name = name,
                createdAt = now,
                updatedAt = now,
            ),
        )
        // The owner is a member like anyone else. Special casing the owner in
        // the permission check is how the check ends up with two code paths and
        // one of them is wrong.
        members.save(
            SheetMemberEntity(
                sheetId = sheet.id,
                userId = ownerId.value,
                role = SheetRole.OWNER,
                createdAt = now,
            ),
        )
        return sheet
    }

    @Transactional(readOnly = true)
    fun page(userId: UserId, cursor: SheetCursor?, limit: Int): List<SheetEntity> {
        // One more than asked for, so the caller can tell whether another page
        // exists without a second count query.
        val window = Limit.of(limit + 1)
        return if (cursor == null) {
            sheets.firstPageForUser(userId.value, window)
        } else {
            sheets.nextPageForUser(userId.value, cursor.createdAt, cursor.id, window)
        }
    }

    @Transactional(readOnly = true)
    fun get(sheetId: SheetId, userId: UserId): SheetEntity = access.requireRead(sheetId, userId)

    @Transactional(readOnly = true)
    fun columnsOf(sheetId: SheetId): List<ColumnEntity> =
        columns.findBySheetIdOrderByPositionAsc(sheetId.value)

    @Transactional
    fun addMember(sheetId: SheetId, actorId: UserId, email: String, role: SheetRole): SheetMemberEntity {
        access.requireShare(sheetId, actorId)
        if (role == SheetRole.OWNER) {
            // Transferring ownership is a different operation with different
            // consequences, and it is not in this phase.
            throw UnprocessableException("A sheet has exactly one owner and it cannot be reassigned here.")
        }
        val user = users.findByEmailIgnoringCase(email)
            ?: throw NotFoundException("No user with that email.")
        val existing = members.findBySheetIdAndUserId(sheetId.value, user.id)
        if (existing != null) {
            existing.role = role
            return members.save(existing)
        }
        return members.save(
            SheetMemberEntity(
                sheetId = sheetId.value,
                userId = user.id,
                role = role,
                createdAt = Instant.now(),
            ),
        )
    }

    @Transactional
    fun addColumn(sheetId: SheetId, actorId: UserId, name: String, type: ColumnType): ColumnEntity {
        access.requireStructure(sheetId, actorId)
        if (columns.existsByNameIgnoringCase(sheetId.value, name)) {
            throw UnprocessableException("This sheet already has a column called '$name'.")
        }
        if (columns.findBySheetIdOrderByPositionAsc(sheetId.value).size >= MAX_COLUMNS) {
            throw UnprocessableException("A sheet may have at most $MAX_COLUMNS columns.")
        }
        // saveAndFlush for the same reason as in RowService: the cell inserts
        // below are raw SQL and cannot see an unflushed persistence context.
        val column = columns.saveAndFlush(
            ColumnEntity(
                id = UUID.randomUUID(),
                sheetId = sheetId.value,
                name = name,
                type = type,
                position = columns.maxPosition(sheetId.value) + 1,
                createdAt = Instant.now(),
            ),
        )
        // Every existing row gains an empty cell in the new column, in this same
        // transaction. Cells are dense on purpose, so a write is a plain UPDATE
        // with a version check and never an upsert. See CellRepository.
        cells.createEmptyCellsForColumn(
            sheetId = sheetId,
            columnId = ColumnId(column.id),
            rowIds = cells.rowIdsOf(sheetId),
            createdBy = actorId,
        )
        return column
    }

    @Transactional(readOnly = true)
    fun memberCount(sheetId: SheetId): Int = members.findBySheetId(sheetId.value).size

    @Transactional(readOnly = true)
    fun rowCount(sheetId: SheetId): Long = rows.countBySheetId(sheetId.value)

    data class SheetCursor(val createdAt: Instant, val id: UUID)

    companion object {
        const val MAX_COLUMNS = 100
    }
}
