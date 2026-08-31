package com.dfsystems.gridwork.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * The rule that makes concurrent editing safe, per ADR 0001.
 *
 * A writer says which version it expects. If the cell moved on, the write is
 * rejected and the caller is told the current version. No locks, no lost
 * updates. This is the single most important rule in the system, so it is
 * tested on its own before anything builds on it.
 */
class VersionRuleTest {

    @Test
    fun `a write that expects the current version is allowed`() {
        VersionRule.check(expected = Version(4), actual = Version(4))
            .shouldBeInstanceOf<VersionCheck.Match>()
    }

    @Test
    fun `a write that expects a stale version is a conflict`() {
        val result = VersionRule.check(expected = Version(3), actual = Version(7))
        result.shouldBeInstanceOf<VersionCheck.Conflict>()
        result.expected shouldBe Version(3)
        result.actual shouldBe Version(7)
    }

    @Test
    fun `a write that expects a version from the future is also a conflict`() {
        // Not a theoretical case. A client that replays a response, or reads
        // from a stale replica, can hold a version this row has never had.
        // Treating it as a conflict is right; treating it as a match would let
        // it overwrite.
        VersionRule.check(expected = Version(9), actual = Version(2))
            .shouldBeInstanceOf<VersionCheck.Conflict>()
    }

    @Test
    fun `a new resource starts at version one`() {
        Version.INITIAL shouldBe Version(1)
    }

    @Test
    fun `a successful write advances the version by exactly one`() {
        Version(1).next() shouldBe Version(2)
        Version(41).next() shouldBe Version(42)
    }

    @Test
    fun `version zero and below are rejected at construction`() {
        // Zero is the value an uninitialised integer field takes. Refusing it
        // means a bug cannot masquerade as a legitimate version.
        shouldThrow<IllegalArgumentException> { Version(0) }
        shouldThrow<IllegalArgumentException> { Version(-1) }
    }
}
