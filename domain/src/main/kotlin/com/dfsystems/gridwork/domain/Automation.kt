package com.dfsystems.gridwork.domain

import java.math.BigDecimal
import java.util.UUID

@JvmInline
value class AutomationId(val value: UUID) {
    override fun toString(): String = value.toString()
}

/** What starts an automation. */
sealed interface Trigger {
    val columnId: ColumnId

    /** Any change to this column. */
    data class ColumnChanged(override val columnId: ColumnId) : Trigger

    /** A change to this column that lands on a particular value. */
    data class ColumnChangedTo(override val columnId: ColumnId, val value: String?) : Trigger
}

enum class Comparator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    GREATER_THAN,
    LESS_THAN,
    IS_EMPTY,
    IS_NOT_EMPTY,
}

/** A test against another cell in the same row. All conditions must hold. */
data class Condition(val columnId: ColumnId, val comparator: Comparator, val value: String?) {

    fun holds(actual: String?): Boolean = when (comparator) {
        Comparator.EQUALS -> actual == value
        Comparator.NOT_EQUALS -> actual != value
        Comparator.IS_EMPTY -> actual.isNullOrEmpty()
        Comparator.IS_NOT_EMPTY -> !actual.isNullOrEmpty()
        // Both sides were typed by a person, so case is not meaningful here.
        Comparator.CONTAINS -> actual != null && value != null &&
            actual.contains(value, ignoreCase = true)
        // Compared as numbers, not as text. "9" > "10" is true as strings and
        // false as numbers, and it is the numeric answer anyone means.
        Comparator.GREATER_THAN -> compareNumerically(actual)?.let { it > 0 } ?: false
        Comparator.LESS_THAN -> compareNumerically(actual)?.let { it < 0 } ?: false
    }

    private fun compareNumerically(actual: String?): Int? {
        val left = actual?.toBigDecimalOrNull() ?: return null
        val right = value?.toBigDecimalOrNull() ?: return null
        return left.compareTo(right)
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(this)
    } catch (_: NumberFormatException) {
        null
    }
}

/** What an automation does. Setting a cell is the only action in this project. */
sealed interface Action {
    data class SetCell(val columnId: ColumnId, val value: String?) : Action
}

data class Automation(
    val id: AutomationId,
    val sheetId: SheetId,
    val name: String,
    val enabled: Boolean,
    val trigger: Trigger,
    val conditions: List<Condition>,
    val action: Action,
)

/** A committed cell change, as the automation engine sees it. */
data class CellChange(
    val sheetId: SheetId,
    val rowId: RowId,
    val columnId: ColumnId,
    val oldValue: String?,
    val newValue: String?,
    /**
     * How many automations deep this change already is. A human edit is zero.
     * A change made by an automation reacting to a human edit is one.
     */
    val depth: Int,
)

/** Something the engine decided should happen. */
data class PlannedAction(
    val automationId: AutomationId,
    val sheetId: SheetId,
    val rowId: RowId,
    val columnId: ColumnId,
    val value: String?,
    val depth: Int,
)

enum class SkipReason {
    NO_MATCHING_AUTOMATION,
    VALUE_UNCHANGED,
}

sealed interface AutomationOutcome {
    data class Fire(val actions: List<PlannedAction>) : AutomationOutcome
    data class Nothing(val reason: SkipReason) : AutomationOutcome

    /**
     * The change is already as deep as automations are allowed to go. Reported
     * rather than silently dropped, so it can be logged and counted: a system
     * hitting this regularly has an automation loop somebody should look at.
     */
    data class DepthExceeded(val depth: Int) : AutomationOutcome
}

sealed interface AutomationValidation {
    data object Valid : AutomationValidation
    data class Invalid(val reason: String) : AutomationValidation
}

/**
 * Decides what a cell change should set off.
 *
 * Pure. It performs nothing, reads nothing, and has never heard of SQS. The
 * worker supplies a change and the row it happened in, and gets back a list of
 * writes to make. That is what lets the loop rule and every comparator be
 * tested without a queue, a database, or a clock.
 *
 * Loops are handled in two places, because one is not enough:
 *
 *   [validate] catches the automation that writes its own trigger column,
 *   which is the tightest loop and can be refused before it ever runs.
 *
 *   [evaluate] enforces a depth limit, which is the only defence against two
 *   automations pointing at each other. No save time check can see that, since
 *   each one is individually reasonable.
 */
object AutomationRule {

    /** Per CLAUDE.md: an automation that triggers itself stops at depth three. */
    const val MAX_DEPTH = 3

    fun validate(automation: Automation): AutomationValidation {
        val action = automation.action
        if (action is Action.SetCell && action.columnId == automation.trigger.columnId) {
            return AutomationValidation.Invalid(
                "This automation is triggered by a change to a column and then writes that " +
                    "same column, so it would trigger itself.",
            )
        }
        return AutomationValidation.Valid
    }

    fun evaluate(
        change: CellChange,
        automations: List<Automation>,
        rowValues: Map<ColumnId, String?>,
    ): AutomationOutcome {
        // A save that did not alter the value is not a change. Without this,
        // clicking into a cell and out again would set off notifications.
        if (change.oldValue == change.newValue) {
            return AutomationOutcome.Nothing(SkipReason.VALUE_UNCHANGED)
        }

        if (change.depth >= MAX_DEPTH) return AutomationOutcome.DepthExceeded(change.depth)

        val actions = automations
            .filter { it.enabled && it.sheetId == change.sheetId }
            .filter { triggers(it.trigger, change) }
            .filter { it.conditions.all { condition -> condition.holds(rowValues[condition.columnId]) } }
            .mapNotNull { automation ->
                when (val action = automation.action) {
                    is Action.SetCell -> {
                        // Writing the value that is already there is not worth
                        // a version bump, and two automations pointing at each
                        // other would otherwise spin until the depth limit
                        // caught them, conflicting with real edits on the way.
                        if (rowValues[action.columnId] == action.value) {
                            null
                        } else {
                            PlannedAction(
                                automationId = automation.id,
                                sheetId = automation.sheetId,
                                rowId = change.rowId,
                                columnId = action.columnId,
                                value = action.value,
                                depth = change.depth + 1,
                            )
                        }
                    }
                }
            }

        return if (actions.isEmpty()) {
            AutomationOutcome.Nothing(SkipReason.NO_MATCHING_AUTOMATION)
        } else {
            AutomationOutcome.Fire(actions)
        }
    }

    private fun triggers(trigger: Trigger, change: CellChange): Boolean = when (trigger) {
        is Trigger.ColumnChanged -> trigger.columnId == change.columnId
        is Trigger.ColumnChangedTo ->
            trigger.columnId == change.columnId && trigger.value == change.newValue
    }
}
