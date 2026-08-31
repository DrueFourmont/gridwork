package com.dfsystems.gridwork.domain

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Phase 0 scaffold check. Proves the domain module compiles and that the test
 * runner and the kotest assertion library are wired up, before any domain code
 * exists to test. Delete this once real domain tests land in Phase 1.
 */
class ModuleWiringTest {
    @Test
    fun `test toolchain is wired`() {
        (2 + 2) shouldBe 4
    }
}
