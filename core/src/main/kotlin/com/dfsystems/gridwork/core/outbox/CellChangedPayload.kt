package com.dfsystems.gridwork.core.outbox

/**
 * What travels through the outbox to SQS and on to the worker.
 *
 * Deliberately self contained: every field the automation engine needs is
 * here, so the worker does not have to read the cell back to find out what
 * happened. By the time it processes the message the cell may have changed
 * again, and reacting to a later value than the one that triggered you is how
 * an automation does something nobody asked for.
 */
data class CellChangedPayload(
    val sheetId: String,
    val rowId: String,
    val columnId: String,
    val oldValue: String?,
    val newValue: String?,
    val version: Long,
    val changedBy: String,
    /** How many automations deep this change already is. A human edit is zero. */
    val depth: Int,
) {
    companion object {
        const val AGGREGATE_TYPE = "sheet"
        const val EVENT_TYPE = "cell.changed"
    }
}
