package com.dfsystems.gridwork.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.test.Test

/**
 * The automation evaluator: trigger, condition, action.
 *
 * The same shape as Smartsheet's, and the reason the project has a queue at
 * all. It is pure: given a change and a set of automations, it decides what
 * should happen. It performs nothing, reads nothing, and knows nothing about
 * SQS, which is what makes it testable like this.
 */
class AutomationRuleTest {

    private val statusColumn = ColumnId(UUID.randomUUID())
    private val ownerColumn = ColumnId(UUID.randomUUID())
    private val doneColumn = ColumnId(UUID.randomUUID())
    private val row = RowId(UUID.randomUUID())
    private val sheet = SheetId(UUID.randomUUID())

    private fun automation(
        trigger: Trigger,
        conditions: List<Condition> = emptyList(),
        action: Action = Action.SetCell(doneColumn, "true"),
        enabled: Boolean = true,
    ) = Automation(
        id = AutomationId(UUID.randomUUID()),
        sheetId = sheet,
        name = "test",
        enabled = enabled,
        trigger = trigger,
        conditions = conditions,
        action = action,
    )

    private fun change(
        column: ColumnId = statusColumn,
        from: String? = null,
        to: String? = "Done",
        depth: Int = 0,
    ) = CellChange(
        sheetId = sheet,
        rowId = row,
        columnId = column,
        oldValue = from,
        newValue = to,
        depth = depth,
    )

    // ------------------------------------------------------------ triggers ----

    @Test
    fun `a change to the watched column fires the automation`() {
        val outcome = AutomationRule.evaluate(
            change = change(),
            automations = listOf(automation(Trigger.ColumnChanged(statusColumn))),
            rowValues = emptyMap(),
        )
        outcome.shouldBeInstanceOf<AutomationOutcome.Fire>()
        outcome.actions shouldHaveSize 1
    }

    @Test
    fun `a change to a different column does not fire it`() {
        val outcome = AutomationRule.evaluate(
            change = change(column = ownerColumn),
            automations = listOf(automation(Trigger.ColumnChanged(statusColumn))),
            rowValues = emptyMap(),
        )
        outcome.shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `a trigger that waits for a specific value ignores other values`() {
        val automations = listOf(automation(Trigger.ColumnChangedTo(statusColumn, "Done")))

        AutomationRule.evaluate(change(to = "Done"), automations, emptyMap())
            .shouldBeInstanceOf<AutomationOutcome.Fire>()
        AutomationRule.evaluate(change(to = "In progress"), automations, emptyMap())
            .shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `a disabled automation never fires`() {
        AutomationRule.evaluate(
            change(),
            listOf(automation(Trigger.ColumnChanged(statusColumn), enabled = false)),
            emptyMap(),
        ).shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `a change that did not actually change the value does not fire anything`() {
        // Saving a cell without editing it must not set off automations, or
        // simply opening and closing a cell would notify everyone.
        AutomationRule.evaluate(
            change(from = "Done", to = "Done"),
            listOf(automation(Trigger.ColumnChanged(statusColumn))),
            emptyMap(),
        ).shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    // ---------------------------------------------------------- conditions ----

    @Test
    fun `a condition on another column is checked against that row`() {
        val automations = listOf(
            automation(
                trigger = Trigger.ColumnChanged(statusColumn),
                conditions = listOf(Condition(ownerColumn, Comparator.EQUALS, "alice")),
            ),
        )

        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to "alice"))
            .shouldBeInstanceOf<AutomationOutcome.Fire>()
        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to "bob"))
            .shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `every condition must hold, not just one`() {
        val automations = listOf(
            automation(
                trigger = Trigger.ColumnChanged(statusColumn),
                conditions = listOf(
                    Condition(ownerColumn, Comparator.EQUALS, "alice"),
                    Condition(doneColumn, Comparator.EQUALS, "false"),
                ),
            ),
        )

        AutomationRule.evaluate(
            change(), automations, mapOf(ownerColumn to "alice", doneColumn to "false"),
        ).shouldBeInstanceOf<AutomationOutcome.Fire>()
        AutomationRule.evaluate(
            change(), automations, mapOf(ownerColumn to "alice", doneColumn to "true"),
        ).shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `comparators handle numbers numerically, not as strings`() {
        // "9" is greater than "10" as text and smaller as a number. Getting
        // this wrong makes every threshold automation quietly incorrect.
        val automations = listOf(
            automation(
                trigger = Trigger.ColumnChanged(statusColumn),
                conditions = listOf(Condition(ownerColumn, Comparator.GREATER_THAN, "10")),
            ),
        )
        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to "9"))
            .shouldBeInstanceOf<AutomationOutcome.Nothing>()
        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to "11"))
            .shouldBeInstanceOf<AutomationOutcome.Fire>()
    }

    @Test
    fun `a numeric comparator on text that is not a number does not match`() {
        val automations = listOf(
            automation(
                trigger = Trigger.ColumnChanged(statusColumn),
                conditions = listOf(Condition(ownerColumn, Comparator.GREATER_THAN, "10")),
            ),
        )
        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to "banana"))
            .shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `is empty distinguishes an unset cell from the text 'empty'`() {
        val automations = listOf(
            automation(
                trigger = Trigger.ColumnChanged(statusColumn),
                conditions = listOf(Condition(ownerColumn, Comparator.IS_EMPTY, null)),
            ),
        )
        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to null))
            .shouldBeInstanceOf<AutomationOutcome.Fire>()
        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to "empty"))
            .shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `contains is case insensitive, because a human typed both sides`() {
        val automations = listOf(
            automation(
                trigger = Trigger.ColumnChanged(statusColumn),
                conditions = listOf(Condition(ownerColumn, Comparator.CONTAINS, "URGENT")),
            ),
        )
        AutomationRule.evaluate(change(), automations, mapOf(ownerColumn to "this is urgent"))
            .shouldBeInstanceOf<AutomationOutcome.Fire>()
    }

    // --------------------------------------------------------------- loops ----

    @Test
    fun `an automation whose action writes its own trigger column is refused at save time`() {
        // The tightest possible loop, and it can be caught before it ever runs.
        AutomationRule.validate(
            automation(
                trigger = Trigger.ColumnChanged(statusColumn),
                action = Action.SetCell(statusColumn, "anything"),
            ),
        ).shouldBeInstanceOf<AutomationValidation.Invalid>()
    }

    @Test
    fun `a normal automation validates`() {
        AutomationRule.validate(automation(Trigger.ColumnChanged(statusColumn)))
            .shouldBeInstanceOf<AutomationValidation.Valid>()
    }

    @Test
    fun `a change at the depth limit fires nothing further`() {
        // Two automations can point at each other, which no save time check
        // can see. The depth carried on the change is what stops it, per
        // CLAUDE.md: an automation that triggers itself stops at depth three.
        val outcome = AutomationRule.evaluate(
            change = change(depth = AutomationRule.MAX_DEPTH),
            automations = listOf(automation(Trigger.ColumnChanged(statusColumn))),
            rowValues = emptyMap(),
        )
        outcome.shouldBeInstanceOf<AutomationOutcome.DepthExceeded>()
        outcome.depth shouldBe AutomationRule.MAX_DEPTH
    }

    @Test
    fun `a change one below the depth limit still fires`() {
        AutomationRule.evaluate(
            change(depth = AutomationRule.MAX_DEPTH - 1),
            listOf(automation(Trigger.ColumnChanged(statusColumn))),
            emptyMap(),
        ).shouldBeInstanceOf<AutomationOutcome.Fire>()
    }

    @Test
    fun `an action carries a depth one greater than the change that caused it`() {
        val outcome = AutomationRule.evaluate(
            change(depth = 1),
            listOf(automation(Trigger.ColumnChanged(statusColumn))),
            emptyMap(),
        )
        outcome.shouldBeInstanceOf<AutomationOutcome.Fire>()
        outcome.actions.first().depth shouldBe 2
    }

    @Test
    fun `an action that would write the value already there is dropped`() {
        // Otherwise two automations pointing at each other keep firing forever
        // while writing nothing, burning versions and conflicting with real
        // edits. The depth limit would stop it, but not before three rounds of
        // pointless writes.
        val outcome = AutomationRule.evaluate(
            change = change(),
            automations = listOf(automation(Trigger.ColumnChanged(statusColumn))),
            rowValues = mapOf(doneColumn to "true"),
        )
        outcome.shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `several automations on one change all fire`() {
        val outcome = AutomationRule.evaluate(
            change = change(),
            automations = listOf(
                automation(Trigger.ColumnChanged(statusColumn), action = Action.SetCell(doneColumn, "true")),
                automation(Trigger.ColumnChanged(statusColumn), action = Action.SetCell(ownerColumn, "system")),
            ),
            rowValues = emptyMap(),
        )
        outcome.shouldBeInstanceOf<AutomationOutcome.Fire>()
        outcome.actions shouldHaveSize 2
    }

    @Test
    fun `no automations means nothing to do`() {
        val outcome = AutomationRule.evaluate(change(), emptyList(), emptyMap())
        outcome.shouldBeInstanceOf<AutomationOutcome.Nothing>()
        AutomationRule.evaluate(change(), emptyList(), emptyMap())
            .let { (it as AutomationOutcome.Nothing).reason }
            .shouldBe(SkipReason.NO_MATCHING_AUTOMATION)
    }

    @Test
    fun `an automation on a different sheet is not considered`() {
        val other = automation(Trigger.ColumnChanged(statusColumn)).copy(sheetId = SheetId(UUID.randomUUID()))
        AutomationRule.evaluate(change(), listOf(other), emptyMap())
            .shouldBeInstanceOf<AutomationOutcome.Nothing>()
    }

    @Test
    fun `the depth limit is three, as the plan says`() {
        AutomationRule.MAX_DEPTH shouldBe 3
    }

    @Test
    fun `fire outcomes never contain an action at or beyond the limit`() {
        val outcome = AutomationRule.evaluate(
            change(depth = AutomationRule.MAX_DEPTH - 1),
            listOf(automation(Trigger.ColumnChanged(statusColumn))),
            emptyMap(),
        )
        outcome.shouldBeInstanceOf<AutomationOutcome.Fire>()
        outcome.actions.filter { it.depth > AutomationRule.MAX_DEPTH }.shouldBeEmpty()
    }
}
