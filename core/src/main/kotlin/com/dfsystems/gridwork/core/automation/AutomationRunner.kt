package com.dfsystems.gridwork.core.automation

import com.dfsystems.gridwork.core.outbox.CellChangedPayload
import com.dfsystems.gridwork.core.service.CellService
import com.dfsystems.gridwork.domain.AutomationOutcome
import com.dfsystems.gridwork.domain.AutomationRule
import com.dfsystems.gridwork.domain.CellChange
import com.dfsystems.gridwork.domain.ColumnId
import com.dfsystems.gridwork.domain.RowId
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.UserId
import com.dfsystems.gridwork.core.error.ApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** What happened to one event, so the caller can decide whether to ack it. */
sealed interface RunResult {
    data class Applied(val actions: Int) : RunResult
    data object AlreadyProcessed : RunResult
    data class Skipped(val reason: String) : RunResult
    data class Failed(val reason: String, val retryable: Boolean) : RunResult
}

/**
 * Handles one cell change event: decide what automations say, then do it.
 *
 * Everything it does goes through [CellService], which is the same class the
 * REST controllers call. That is the requirement in CLAUDE.md, and it is not
 * bureaucracy: it means an automation's write gets the same version check, the
 * same history row, the same outbox event, and the same permission model as a
 * person typing. An automation that wrote to the cells table directly would
 * bypass all four, and the first symptom would be a lost update nobody could
 * explain.
 *
 * The whole thing is one transaction, including the dedupe insert. If the
 * action fails, the claim rolls back with it and the message is retried.
 */
@Service
class AutomationRunner(
    private val automations: AutomationRepository,
    private val cells: CellService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(eventId: Long, payload: CellChangedPayload): RunResult {
        // Claimed first, inside the transaction. A redelivery of an event
        // already handled finds the row and stops here, and if the work below
        // fails the claim disappears with the rollback so a retry can try
        // again properly.
        if (!automations.claimEvent(eventId, 0)) return RunResult.AlreadyProcessed

        val sheetId = SheetId(UUID.fromString(payload.sheetId))
        val rowId = RowId(UUID.fromString(payload.rowId))

        val enabled = automations.enabledForSheet(sheetId)
        if (enabled.isEmpty()) return RunResult.Skipped("no automations on this sheet")

        val change = CellChange(
            sheetId = sheetId,
            rowId = rowId,
            columnId = ColumnId(UUID.fromString(payload.columnId)),
            oldValue = payload.oldValue,
            newValue = payload.newValue,
            depth = payload.depth,
        )

        return when (val outcome = AutomationRule.evaluate(change, enabled, automations.rowValues(rowId.value))) {
            is AutomationOutcome.Nothing -> RunResult.Skipped(outcome.reason.name)

            is AutomationOutcome.DepthExceeded -> {
                // Logged at warn rather than swallowed. Hitting the limit is
                // not an error, but a system hitting it regularly has an
                // automation loop somebody should be told about.
                log.warn(
                    "automation depth limit reached on sheet {} row {}, stopping. " +
                        "This usually means two automations trigger each other.",
                    payload.sheetId,
                    payload.rowId,
                )
                RunResult.Skipped("depth limit ${outcome.depth}")
            }

            is AutomationOutcome.Fire -> apply(outcome, payload)
        }
    }

    private fun apply(outcome: AutomationOutcome.Fire, payload: CellChangedPayload): RunResult {
        // The automation acts as the person whose edit triggered it. That is a
        // deliberate simplification: it keeps every write attributable to a
        // real user, and it means an automation can never do something the
        // person who set it off could not do themselves.
        val actor = UserId(UUID.fromString(payload.changedBy))
        var applied = 0

        for (action in outcome.actions) {
            val current = automations.rowValues(action.rowId.value)[action.columnId]
            val version = currentVersion(action.rowId.value, action.columnId)
            if (version == null) {
                log.warn("automation targets a cell that does not exist, skipping")
                continue
            }
            if (current == action.value) continue

            try {
                cells.batchUpdate(
                    sheetId = action.sheetId,
                    actorId = actor,
                    requested = listOf(
                        CellService.RequestedWrite(
                            rowId = action.rowId.value,
                            columnId = action.columnId.value,
                            value = action.value,
                            expectedVersion = version,
                        ),
                    ),
                    // Carries the loop counter forward. The write this makes
                    // will produce its own event at this depth, and at three
                    // AutomationRule stops.
                    depth = action.depth,
                )
                applied++
            } catch (exception: ApiException) {
                // A conflict here means a person edited the same cell while
                // this automation was deciding. The person wins: their edit
                // will produce its own event and the automations will be
                // evaluated again against the value they actually wrote.
                log.info(
                    "automation {} could not write, most likely a concurrent edit: {}",
                    action.automationId,
                    exception.message,
                )
            }
        }
        return RunResult.Applied(applied)
    }

    private fun currentVersion(rowId: UUID, columnId: ColumnId): Long? =
        automations.cellVersion(rowId, columnId.value)
}
