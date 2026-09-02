package com.dfsystems.gridwork.core.automation

import com.dfsystems.gridwork.domain.Action
import com.dfsystems.gridwork.domain.Automation
import com.dfsystems.gridwork.domain.AutomationId
import com.dfsystems.gridwork.domain.ColumnId
import com.dfsystems.gridwork.domain.Comparator
import com.dfsystems.gridwork.domain.Condition
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.Trigger
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AutomationRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Every enabled automation on a sheet, with its conditions.
     *
     * Two queries rather than a join, because a join would repeat each
     * automation once per condition and the grouping would have to be undone
     * in Kotlin anyway. A sheet has a handful of automations, so this is two
     * small reads on the worker's hot path.
     */
    fun enabledForSheet(sheetId: SheetId): List<Automation> {
        val automations = jdbc.query(
            """
            select id, sheet_id, name, enabled, trigger_type, trigger_column_id,
                   trigger_value, action_column_id, action_value
            from automations
            where sheet_id = :sheetId and enabled
            order by created_at
            """.trimIndent(),
            MapSqlParameterSource("sheetId", sheetId.value),
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val triggerColumn = ColumnId(rs.getObject("trigger_column_id", UUID::class.java))
            id to Automation(
                id = AutomationId(id),
                sheetId = SheetId(rs.getObject("sheet_id", UUID::class.java)),
                name = rs.getString("name"),
                enabled = rs.getBoolean("enabled"),
                trigger = when (rs.getString("trigger_type")) {
                    "COLUMN_CHANGED_TO" ->
                        Trigger.ColumnChangedTo(triggerColumn, rs.getString("trigger_value"))
                    else -> Trigger.ColumnChanged(triggerColumn)
                },
                conditions = emptyList(),
                action = Action.SetCell(
                    ColumnId(rs.getObject("action_column_id", UUID::class.java)),
                    rs.getString("action_value"),
                ),
            )
        }
        if (automations.isEmpty()) return emptyList()

        val conditions = jdbc.query(
            """
            select automation_id, column_id, comparator, value
            from automation_conditions
            where automation_id in (:ids)
            order by id
            """.trimIndent(),
            MapSqlParameterSource("ids", automations.map { it.first }),
        ) { rs, _ ->
            rs.getObject("automation_id", UUID::class.java) to Condition(
                columnId = ColumnId(rs.getObject("column_id", UUID::class.java)),
                comparator = Comparator.valueOf(rs.getString("comparator")),
                value = rs.getString("value"),
            )
        }.groupBy({ it.first }, { it.second })

        return automations.map { (id, automation) ->
            automation.copy(conditions = conditions[id].orEmpty())
        }
    }

    fun rowValues(rowId: UUID): Map<ColumnId, String?> = jdbc.query(
        "select column_id, value from cells where row_id = :rowId",
        MapSqlParameterSource("rowId", rowId),
    ) { rs, _ ->
        ColumnId(rs.getObject("column_id", UUID::class.java)) to rs.getString("value")
    }.toMap()

    /**
     * Records that an event has been handled, returning false if it already had.
     *
     * `on conflict do nothing` makes this the dedupe itself rather than a
     * check followed by a write, so two workers racing the same redelivered
     * message cannot both proceed. Same trick as the idempotency keys in
     * ADR 0003, for the same reason.
     */
    fun claimEvent(eventId: Long, actionsTaken: Int): Boolean =
        jdbc.update(
            """
            insert into processed_events (event_id, actions_taken)
            values (:eventId, :actions)
            on conflict (event_id) do nothing
            """.trimIndent(),
            MapSqlParameterSource().addValue("eventId", eventId).addValue("actions", actionsTaken),
        ) == 1

    /** The version a cell is at now, so an automation's write can expect it. */
    fun cellVersion(rowId: UUID, columnId: UUID): Long? = jdbc.query(
        "select version from cells where row_id = :rowId and column_id = :columnId",
        MapSqlParameterSource().addValue("rowId", rowId).addValue("columnId", columnId),
    ) { rs, _ -> rs.getLong("version") }.firstOrNull()

    fun hasProcessed(eventId: Long): Boolean =
        (
            jdbc.queryForObject(
                "select count(*) from processed_events where event_id = :eventId",
                MapSqlParameterSource("eventId", eventId),
                Int::class.java,
            ) ?: 0
            ) > 0
}
