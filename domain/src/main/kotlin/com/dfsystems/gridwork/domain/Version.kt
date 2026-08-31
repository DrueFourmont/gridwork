package com.dfsystems.gridwork.domain

/**
 * The optimistic concurrency version of a mutable resource, per ADR 0001.
 *
 * Starts at 1 and increases by exactly one per successful write. Zero is
 * refused deliberately: it is what an uninitialised long looks like, so
 * refusing it means a bug cannot pass itself off as a valid version.
 */
@JvmInline
value class Version(val value: Long) {

    init {
        require(value >= 1) { "version must be 1 or greater, was $value" }
    }

    fun next(): Version = Version(value + 1)

    override fun toString(): String = value.toString()

    companion object {
        val INITIAL = Version(1)
    }
}

/** The result of comparing the version a writer expected against reality. */
sealed interface VersionCheck {
    data object Match : VersionCheck
    data class Conflict(val expected: Version, val actual: Version) : VersionCheck
}

object VersionRule {

    /**
     * A write is allowed only when it expected exactly the current version.
     *
     * Note that an expected version HIGHER than the actual one is also a
     * conflict. It is tempting to treat it as harmless, but a client holding a
     * version this row has never had is a client whose view of the world is
     * wrong, and letting it write would overwrite whatever is really there.
     */
    fun check(expected: Version, actual: Version): VersionCheck =
        if (expected == actual) VersionCheck.Match else VersionCheck.Conflict(expected, actual)
}
