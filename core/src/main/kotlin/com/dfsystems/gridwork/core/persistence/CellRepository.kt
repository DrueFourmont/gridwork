package com.dfsystems.gridwork.core.persistence

import com.dfsystems.gridwork.domain.CellAddress
import com.dfsystems.gridwork.domain.CellWrite
import com.dfsystems.gridwork.domain.ColumnId
import com.dfsystems.gridwork.domain.RowId
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.UserId
import com.dfsystems.gridwork.domain.Version
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** A cell as it exists in the database right now. */
data class StoredCell(
    val address: CellAddress,
    val value: String?,
    val version: Version,
)

/**
 * Plain SQL, no ORM, per docs/PLAN-SUMMARY.md.
 *
 * Two reasons. Reads: a page of the grid is thousands of cells, and letting an
 * ORM hydrate an object per cell is how a 60 fps budget disappears. Writes: the
 * conditional update in [applyBatch] has to be exactly
 * `... where version = :expected`, and it has to run as one batch statement, so
 * that the version check and the write are a single atomic step. An ORM would
 * read, compare in Java, and write, which is a race with a longer window.
 */
@Repository
class CellRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findBySheetAndRows(sheetId: SheetId, rowIds: List<RowId>): List<StoredCell> {
        if (rowIds.isEmpty()) return emptyList()
        return jdbc.query(
            """
            select row_id, column_id, value, version
            from cells
            where sheet_id = :sheetId and row_id in (:rowIds)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("sheetId", sheetId.value)
                .addValue("rowIds", rowIds.map { it.value }),
        ) { rs, _ -> rs.toStoredCell() }
    }

    fun findByAddresses(sheetId: SheetId, addresses: List<CellAddress>): List<StoredCell> {
        if (addresses.isEmpty()) return emptyList()
        // (row_id, column_id) IN ((..),(..)) keeps this to one index lookup per
        // pair on the primary key, rather than a scan of the sheet.
        val pairs = addresses.joinToString(",") { "(?::uuid, ?::uuid)" }
        val args = addresses.flatMap { listOf(it.rowId.value, it.columnId.value) }.toTypedArray()
        return jdbc.jdbcTemplate.query(
            """
            select row_id, column_id, value, version
            from cells
            where sheet_id = ?::uuid and (row_id, column_id) in ($pairs)
            """.trimIndent(),
            { rs, _ -> rs.toStoredCell() },
            sheetId.value, *args,
        )
    }

    /**
     * Creates the empty cells for a new row, one per column.
     *
     * A row is not a sparse thing here: every (row, column) pair exists from
     * the moment the row does, at version 1. That is what lets a cell write be
     * a pure UPDATE with a version check, with no upsert and no "does this cell
     * exist yet" branch on the hot path.
     */
    fun createEmptyCellsForRow(
        sheetId: SheetId,
        rowId: RowId,
        columnIds: List<ColumnId>,
        createdBy: UserId,
    ) {
        if (columnIds.isEmpty()) return
        val batch = columnIds.map { columnId ->
            MapSqlParameterSource()
                .addValue("rowId", rowId.value)
                .addValue("columnId", columnId.value)
                .addValue("sheetId", sheetId.value)
                .addValue("updatedBy", createdBy.value)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            insert into cells (row_id, column_id, sheet_id, value, version, updated_by)
            values (:rowId, :columnId, :sheetId, null, 1, :updatedBy)
            """.trimIndent(),
            batch,
        )
    }

    /** Creates the empty cells for a new column, one per existing row. */
    fun createEmptyCellsForColumn(
        sheetId: SheetId,
        columnId: ColumnId,
        rowIds: List<RowId>,
        createdBy: UserId,
    ) {
        if (rowIds.isEmpty()) return
        val batch = rowIds.map { rowId ->
            MapSqlParameterSource()
                .addValue("rowId", rowId.value)
                .addValue("columnId", columnId.value)
                .addValue("sheetId", sheetId.value)
                .addValue("updatedBy", createdBy.value)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            insert into cells (row_id, column_id, sheet_id, value, version, updated_by)
            values (:rowId, :columnId, :sheetId, null, 1, :updatedBy)
            """.trimIndent(),
            batch,
        )
    }

    fun rowIdsOf(sheetId: SheetId): List<RowId> = jdbc.query(
        "select id from rows where sheet_id = :sheetId",
        MapSqlParameterSource("sheetId", sheetId.value),
    ) { rs, _ -> RowId(rs.getObject("id", UUID::class.java)) }

    /**
     * Applies a batch of writes, each conditional on its expected version.
     *
     * Returns the number of rows each statement affected. A zero means that
     * cell's version moved between the caller's check and this statement, which
     * is the race the version rule exists to catch. The caller must treat any
     * zero as a conflict and roll the transaction back.
     */
    fun applyBatch(sheetId: SheetId, writes: List<CellWrite>, updatedBy: UserId): IntArray {
        val batch = writes.map { write ->
            MapSqlParameterSource()
                .addValue("value", write.value.asStored())
                .addValue("updatedBy", updatedBy.value)
                .addValue("rowId", write.address.rowId.value)
                .addValue("columnId", write.address.columnId.value)
                .addValue("sheetId", sheetId.value)
                .addValue("expectedVersion", write.expectedVersion.value)
        }.toTypedArray()
        return jdbc.batchUpdate(
            """
            update cells
            set value = :value,
                version = version + 1,
                updated_at = now(),
                updated_by = :updatedBy
            where row_id = :rowId
              and column_id = :columnId
              and sheet_id = :sheetId
              and version = :expectedVersion
            """.trimIndent(),
            batch,
        )
    }

    /**
     * Appends to the audit trail, in the same transaction as the write it
     * describes, and returns the sequence assigned to each entry.
     *
     * The sequences matter beyond the audit: cell_history doubles as the
     * replay log a reconnecting websocket client reads, so a live update has
     * to carry the same sequence a replay would give it. Otherwise a client
     * could not tell whether it had already seen a change.
     */
    fun recordHistory(
        sheetId: SheetId,
        entries: List<HistoryEntry>,
        changedBy: UserId,
    ): List<Long> {
        if (entries.isEmpty()) return emptyList()
        // One multi row insert with a returning clause rather than a batch,
        // because a batchUpdate cannot give back generated keys in order.
        val values = entries.indices.joinToString(",") { index ->
            "(:rowId$index, :columnId$index, :sheetId, :oldValue$index, " +
                ":newValue$index, :version$index, :changedBy)"
        }
        val params = MapSqlParameterSource()
            .addValue("sheetId", sheetId.value)
            .addValue("changedBy", changedBy.value)
        entries.forEachIndexed { index, entry ->
            params.addValue("rowId$index", entry.address.rowId.value)
            params.addValue("columnId$index", entry.address.columnId.value)
            params.addValue("oldValue$index", entry.oldValue)
            params.addValue("newValue$index", entry.newValue)
            params.addValue("version$index", entry.newVersion.value)
        }
        return jdbc.query(
            """
            insert into cell_history
                (row_id, column_id, sheet_id, old_value, new_value, version, changed_by)
            values $values
            returning id
            """.trimIndent(),
            params,
        ) { rs, _ -> rs.getLong("id") }
    }

    data class HistoryEntry(
        val address: CellAddress,
        val oldValue: String?,
        val newValue: String?,
        val newVersion: Version,
    )

    private fun ResultSet.toStoredCell(): StoredCell = StoredCell(
        address = CellAddress(
            RowId(getObject("row_id", UUID::class.java)),
            ColumnId(getObject("column_id", UUID::class.java)),
        ),
        value = getString("value"),
        version = Version(getLong("version")),
    )
}
