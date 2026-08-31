package com.dfsystems.gridwork.domain

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.test.Test

/**
 * A batch of cell writes is all or nothing, per ADR 0001.
 *
 * The interesting requirement is not that conflicts are detected, it is that
 * one conflict aborts the whole batch and the caller is told about EVERY
 * conflicting cell, not just the first. A UI showing a merge dialog needs the
 * full list, otherwise the user fixes one cell, retries, and gets another
 * conflict.
 */
class BatchUpdateRuleTest {

    private fun address() = CellAddress(RowId(UUID.randomUUID()), ColumnId(UUID.randomUUID()))

    @Test
    fun `a batch where every expected version matches is applicable`() {
        val a = address()
        val b = address()
        val outcome = BatchUpdateRule.evaluate(
            requested = listOf(
                CellWrite(a, CellValue.Text("one"), expectedVersion = Version(1)),
                CellWrite(b, CellValue.Text("two"), expectedVersion = Version(5)),
            ),
            currentVersions = mapOf(a to Version(1), b to Version(5)),
        )
        outcome.shouldBeInstanceOf<BatchOutcome.Applicable>()
        outcome.writes shouldHaveSize 2
    }

    @Test
    fun `one stale cell aborts the entire batch`() {
        val fresh = address()
        val stale = address()
        val outcome = BatchUpdateRule.evaluate(
            requested = listOf(
                CellWrite(fresh, CellValue.Text("ok"), expectedVersion = Version(1)),
                CellWrite(stale, CellValue.Text("no"), expectedVersion = Version(1)),
            ),
            currentVersions = mapOf(fresh to Version(1), stale to Version(9)),
        )
        outcome.shouldBeInstanceOf<BatchOutcome.Conflicted>()
    }

    @Test
    fun `every conflicting cell is reported, not just the first`() {
        val ok = address()
        val badOne = address()
        val badTwo = address()
        val outcome = BatchUpdateRule.evaluate(
            requested = listOf(
                CellWrite(ok, CellValue.Text("fine"), expectedVersion = Version(2)),
                CellWrite(badOne, CellValue.Text("x"), expectedVersion = Version(1)),
                CellWrite(badTwo, CellValue.Text("y"), expectedVersion = Version(1)),
            ),
            currentVersions = mapOf(ok to Version(2), badOne to Version(4), badTwo to Version(6)),
        )
        outcome.shouldBeInstanceOf<BatchOutcome.Conflicted>()
        outcome.conflicts.map { it.address } shouldContainExactlyInAnyOrder listOf(badOne, badTwo)
        outcome.conflicts.single { it.address == badOne }.actual shouldBe Version(4)
        outcome.conflicts.single { it.address == badTwo }.actual shouldBe Version(6)
    }

    @Test
    fun `a cell that does not exist yet is reported as unknown, not as a conflict`() {
        // These are different problems and deserve different status codes: a
        // conflict is 409 and retryable after a refetch, an unknown cell is a
        // 404 and retrying will never help.
        val missing = address()
        val outcome = BatchUpdateRule.evaluate(
            requested = listOf(CellWrite(missing, CellValue.Text("x"), expectedVersion = Version(1))),
            currentVersions = emptyMap(),
        )
        outcome.shouldBeInstanceOf<BatchOutcome.UnknownCells>()
        outcome.addresses shouldContainExactlyInAnyOrder listOf(missing)
    }

    @Test
    fun `the same cell twice in one batch is rejected as ambiguous`() {
        // Which write wins? Any answer is a guess about intent. Rejecting is
        // the only option that cannot silently discard someone's edit.
        val a = address()
        val outcome = BatchUpdateRule.evaluate(
            requested = listOf(
                CellWrite(a, CellValue.Text("first"), expectedVersion = Version(1)),
                CellWrite(a, CellValue.Text("second"), expectedVersion = Version(1)),
            ),
            currentVersions = mapOf(a to Version(1)),
        )
        outcome.shouldBeInstanceOf<BatchOutcome.DuplicateCells>()
        outcome.addresses shouldContainExactlyInAnyOrder listOf(a)
    }

    @Test
    fun `an empty batch is rejected rather than treated as a no-op success`() {
        BatchUpdateRule.evaluate(requested = emptyList(), currentVersions = emptyMap())
            .shouldBeInstanceOf<BatchOutcome.EmptyBatch>()
    }

    @Test
    fun `unknown cells are reported even when another cell also conflicts`() {
        // Report the problem that cannot be retried first, so a client is not
        // told to refetch and try again when that will never work.
        val conflicting = address()
        val missing = address()
        val outcome = BatchUpdateRule.evaluate(
            requested = listOf(
                CellWrite(conflicting, CellValue.Text("x"), expectedVersion = Version(1)),
                CellWrite(missing, CellValue.Text("y"), expectedVersion = Version(1)),
            ),
            currentVersions = mapOf(conflicting to Version(3)),
        )
        outcome.shouldBeInstanceOf<BatchOutcome.UnknownCells>()
    }
}
