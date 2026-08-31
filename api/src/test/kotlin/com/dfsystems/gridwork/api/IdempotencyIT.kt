package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.support.ApiIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Retrying an unsafe request must not do the work twice, per ADR 0003.
 *
 * The case that motivates all of this: a client POSTs, the connection drops
 * before the response arrives, and the client has no way to know whether the
 * sheet was created. It has to be able to just retry.
 */
class IdempotencyIT : ApiIntegrationTest() {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `the same key replays the first response instead of creating a second sheet`() {
        val user = register()
        val key = UUID.randomUUID().toString()

        val first = perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Budget"), key)
            .andExpect(status().isCreated)
            .andReturn()
        val firstId = first.json()["id"].asText()

        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Budget"), key)
            .andExpect(status().isCreated)
            .andExpect(header().string("Idempotent-Replay", "true"))
            .andExpect(jsonPath("$.id").value(firstId))

        countSheets() shouldEqual 1
    }

    @Test
    fun `without a key, two identical requests create two sheets`() {
        // The guarantee is opt in. This test exists so the one above is
        // proving the key works rather than proving something else does.
        val user = register()
        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Budget")).andExpect(status().isCreated)
        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Budget")).andExpect(status().isCreated)
        countSheets() shouldEqual 2
    }

    @Test
    fun `the same key with a different body is rejected rather than silently replayed`() {
        val user = register()
        val key = UUID.randomUUID().toString()

        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Budget"), key)
            .andExpect(status().isCreated)

        // Returning the Budget sheet here would be worse than an error: the
        // client asked for Forecast and would be told it got one.
        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Forecast"), key)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different request body")))

        countSheets() shouldEqual 1
    }

    @Test
    fun `two users may use the same key without colliding`() {
        val alice = register()
        val bob = register()
        val key = "shared-key"

        perform(post("/api/v1/sheets"), alice.token, mapOf("name" to "Alice sheet"), key)
            .andExpect(status().isCreated)
        perform(post("/api/v1/sheets"), bob.token, mapOf("name" to "Bob sheet"), key)
            .andExpect(status().isCreated)
            // Not a replay: Bob's key is his own.
            .andExpect(header().doesNotExist("Idempotent-Replay"))

        countSheets() shouldEqual 2
    }

    @Test
    fun `the same key on a different endpoint is a different operation`() {
        val user = register()
        val key = "same-key-two-routes"
        val sheet = perform(post("/api/v1/sheets"), user.token, mapOf("name" to "S"), key)
            .andExpect(status().isCreated).andReturn().json()["id"].asText()

        perform(
            post("/api/v1/sheets/$sheet/columns"), user.token, mapOf("name" to "A", "type" to "TEXT"), key,
        ).andExpect(status().isCreated)
    }

    @Test
    fun `concurrent requests with one key create exactly one sheet`() {
        // The reason the claim is an insert with on conflict do nothing rather
        // than a select then insert. A double click fires both before either
        // has committed.
        val user = register()
        val key = UUID.randomUUID().toString()
        val pool = Executors.newFixedThreadPool(4)
        val tasks = (1..4).map {
            Callable {
                perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Race"), key)
                    .andReturn().response.status
            }
        }
        val statuses = pool.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        // Whatever mix of 201 and 409 comes back, the invariant is one sheet.
        countSheets() shouldEqual 1
        require(statuses.all { it == 201 || it == 409 }) { "unexpected statuses: $statuses" }
    }

    @Test
    fun `a failed operation releases its key so a retry can succeed`() {
        val user = register()
        val key = UUID.randomUUID().toString()

        // A name of 300 characters fails validation, so the operation throws.
        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "x".repeat(300)), key)
            .andExpect(status().isUnprocessableEntity)

        // If the failure had been cached, this would replay the 422 forever.
        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "Valid now"), key)
            .andExpect(status().isCreated)
        countSheets() shouldEqual 1
    }

    @Test
    fun `a batch cell update can be replayed safely`() {
        val user = register()
        val sheet = createSheet(user)
        val column = addColumn(user, sheet, "A", "TEXT")
        val row = addRow(user, sheet)
        val key = UUID.randomUUID().toString()

        batchUpdate(user, sheet, listOf(update(row, column, "once", 1)), key)
            .andExpect(status().isOk)

        // Without the key this retry would be a 409, because the cell is now at
        // version 2 and the replayed request still expects version 1. With the
        // key it returns the original success, which is what a client that lost
        // its connection actually needs.
        batchUpdate(user, sheet, listOf(update(row, column, "once", 1)), key)
            .andExpect(status().isOk)
            .andExpect(header().string("Idempotent-Replay", "true"))
            .andExpect(jsonPath("$.updated[0].version").value(2))
    }

    @Test
    fun `an over long key is rejected`() {
        val user = register()
        perform(post("/api/v1/sheets"), user.token, mapOf("name" to "S"), "k".repeat(300))
            .andExpect(status().isUnprocessableEntity)
    }

    private fun countSheets(): Int =
        jdbc.queryForObject("select count(*) from sheets", Int::class.java) ?: 0

    private infix fun Int.shouldEqual(expected: Int) =
        require(this == expected) { "expected $expected but was $this" }
}
