package com.dfsystems.gridwork.core.persistence

import com.dfsystems.gridwork.core.realtime.Outbound
import com.dfsystems.gridwork.domain.Sequence
import com.dfsystems.gridwork.domain.SheetId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Reads cell_history as a replay log.
 *
 * The table was built in Phase 1 as an audit trail. It turns out to be exactly
 * what a reconnecting client needs, because it is append only and its
 * bigserial id is monotonic, so "everything after sequence N" is a range scan.
 * Building a second log would have meant two things to keep in step.
 */
@Repository
class CellHistoryRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /** The most recent sequence for a sheet, or zero if nothing has ever changed. */
    fun latestSequence(sheetId: SheetId): Sequence {
        val value = jdbc.queryForObject(
            "select coalesce(max(id), 0) from cell_history where sheet_id = :sheetId",
            MapSqlParameterSource("sheetId", sheetId.value),
            Long::class.java,
        )
        return Sequence(value ?: 0)
    }

    /**
     * Changes after [after], oldest first, capped.
     *
     * Only the latest state per cell would be smaller, but sending the changes
     * in order lets a client apply them exactly as if it had been connected,
     * which keeps one code path for live and replayed updates.
     */
    fun changesAfter(sheetId: SheetId, after: Sequence, limit: Long): List<Outbound.CellChanged> =
        jdbc.query(
            """
            select h.id, h.row_id, h.column_id, h.new_value, h.version, h.changed_by
            from cell_history h
            where h.sheet_id = :sheetId and h.id > :after
            order by h.id asc
            limit :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("sheetId", sheetId.value)
                .addValue("after", after.value)
                .addValue("limit", limit),
        ) { rs, _ ->
            Outbound.CellChanged(
                sheetId = sheetId.toString(),
                rowId = rs.getString("row_id"),
                columnId = rs.getString("column_id"),
                value = rs.getString("new_value"),
                version = rs.getLong("version"),
                sequence = rs.getLong("id"),
                changedBy = rs.getString("changed_by"),
            )
        }
}
