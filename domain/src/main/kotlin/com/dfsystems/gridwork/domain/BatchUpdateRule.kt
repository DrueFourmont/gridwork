package com.dfsystems.gridwork.domain

/** One requested cell write: where, what, and the version the writer expected to overwrite. */
data class CellWrite(
    val address: CellAddress,
    val value: CellValue,
    val expectedVersion: Version,
)

/** A cell whose expected version did not match, reported back so a UI can show a merge. */
data class CellConflict(
    val address: CellAddress,
    val expected: Version,
    val actual: Version,
)

sealed interface BatchOutcome {
    /** Every expected version matched. The caller may apply these writes. */
    data class Applicable(val writes: List<CellWrite>) : BatchOutcome

    /** At least one cell moved on. Nothing is applied. Maps to 409. */
    data class Conflicted(val conflicts: List<CellConflict>) : BatchOutcome

    /** At least one address does not exist in this sheet. Maps to 404. */
    data class UnknownCells(val addresses: List<CellAddress>) : BatchOutcome

    /** The same cell appears more than once in one batch. Maps to 422. */
    data class DuplicateCells(val addresses: List<CellAddress>) : BatchOutcome

    /** No writes were requested at all. Maps to 422. */
    data object EmptyBatch : BatchOutcome
}

/**
 * Decides whether a batch of cell writes may be applied, per ADR 0001.
 *
 * All or nothing. One stale cell aborts the whole batch, and the caller is
 * told about every conflicting cell rather than the first one found, because a
 * client that fixes one cell and retries only to hit the next conflict is a
 * client that will retry as many times as there are conflicts.
 *
 * Pure, and deliberately ignorant of transactions and SQL. The service layer
 * supplies the current versions and performs the writes; this decides.
 */
object BatchUpdateRule {

    fun evaluate(
        requested: List<CellWrite>,
        currentVersions: Map<CellAddress, Version>,
    ): BatchOutcome {
        if (requested.isEmpty()) return BatchOutcome.EmptyBatch

        val duplicates = requested
            .groupingBy { it.address }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        // Two writes to one cell in one batch: whichever wins, the other is
        // discarded without the caller knowing. Refusing is the only answer
        // that cannot silently lose an edit.
        if (duplicates.isNotEmpty()) return BatchOutcome.DuplicateCells(duplicates.toList())

        val unknown = requested.map { it.address }.filterNot { currentVersions.containsKey(it) }
        // Checked before conflicts on purpose. A conflict tells the caller to
        // refetch and retry; an unknown cell will never succeed no matter how
        // many times it retries, so that is the more useful thing to report.
        if (unknown.isNotEmpty()) return BatchOutcome.UnknownCells(unknown)

        val conflicts = requested.mapNotNull { write ->
            val actual = currentVersions.getValue(write.address)
            when (val check = VersionRule.check(write.expectedVersion, actual)) {
                is VersionCheck.Match -> null
                is VersionCheck.Conflict -> CellConflict(write.address, check.expected, check.actual)
            }
        }
        return if (conflicts.isEmpty()) {
            BatchOutcome.Applicable(requested)
        } else {
            BatchOutcome.Conflicted(conflicts)
        }
    }
}
