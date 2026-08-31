package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.support.ApiIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The 409, which is the headline requirement of this phase and the reason
 * ADR 0001 exists.
 */
class CellConflictIT : ApiIntegrationTest() {

    @Test
    fun `a write at the current version succeeds and bumps the version by one`() {
        val user = register()
        val sheet = createSheet(user)
        val column = addColumn(user, sheet, "Task", "TEXT")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, column, "first", 1)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updated[0].value").value("first"))
            .andExpect(jsonPath("$.updated[0].version").value(2))
    }

    @Test
    fun `a write at a stale version is rejected with 409 and the current value`() {
        val user = register()
        val sheet = createSheet(user)
        val column = addColumn(user, sheet, "Task", "TEXT")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, column, "winner", 1))).andExpect(status().isOk)

        // A second writer still believes the cell is at version 1.
        batchUpdate(user, sheet, listOf(update(row, column, "loser", 1)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.conflicts[0].expectedVersion").value(1))
            .andExpect(jsonPath("$.conflicts[0].actualVersion").value(2))
            // The current value comes back, so a client can render a merge
            // without a second round trip.
            .andExpect(jsonPath("$.conflicts[0].actualValue").value("winner"))
    }

    @Test
    fun `one stale cell rolls back the whole batch, including the cells that were fine`() {
        val user = register()
        val sheet = createSheet(user)
        val columnA = addColumn(user, sheet, "A", "TEXT")
        val columnB = addColumn(user, sheet, "B", "TEXT")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, columnA, "moved on", 1))).andExpect(status().isOk)

        // Column B is at version 1 and would be perfectly writable on its own.
        batchUpdate(
            user, sheet,
            listOf(
                update(row, columnB, "should not persist", 1),
                update(row, columnA, "stale", 1),
            ),
        ).andExpect(status().isConflict)

        val rows = readRows(user, sheet)
        val cells = rows["items"][0]["cells"].associate { it["columnId"].asText() to it["value"] }
        // This is the assertion that matters. If the transaction leaked, B
        // would hold "should not persist".
        require(cells[columnB]!!.isNull) { "batch was not atomic: column B was written despite the conflict" }
        require(cells[columnA]!!.asText() == "moved on")
    }

    @Test
    fun `every conflicting cell is reported, not just the first`() {
        val user = register()
        val sheet = createSheet(user)
        val columnA = addColumn(user, sheet, "A", "TEXT")
        val columnB = addColumn(user, sheet, "B", "TEXT")
        val row = addRow(user, sheet)

        batchUpdate(
            user, sheet,
            listOf(update(row, columnA, "a1", 1), update(row, columnB, "b1", 1)),
        ).andExpect(status().isOk)

        batchUpdate(
            user, sheet,
            listOf(update(row, columnA, "a2", 1), update(row, columnB, "b2", 1)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.conflicts.length()").value(2))
    }

    @Test
    fun `the conflict body is problem+json and carries the request id`() {
        val user = register()
        val sheet = createSheet(user)
        val column = addColumn(user, sheet, "A", "TEXT")
        val row = addRow(user, sheet)
        batchUpdate(user, sheet, listOf(update(row, column, "x", 1))).andExpect(status().isOk)

        batchUpdate(user, sheet, listOf(update(row, column, "y", 1)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("https://gridwork.dfsystems.co/problems/conflict"))
            .andExpect(jsonPath("$.title").value("Conflict"))
            .andExpect(jsonPath("$.instance").exists())
            .andExpect(jsonPath("$.requestId").exists())
    }

    @Test
    fun `two concurrent writers at the same version, exactly one wins`() {
        val user = register()
        val sheet = createSheet(user)
        val column = addColumn(user, sheet, "A", "TEXT")
        val row = addRow(user, sheet)

        // The race the version rule exists for. Both requests read version 1
        // and both try to write it. Serialising in the application cannot
        // prevent this; only the version in the UPDATE's WHERE clause can.
        val pool = Executors.newFixedThreadPool(2)
        val tasks = listOf("writer-a", "writer-b").map { name ->
            Callable {
                batchUpdate(user, sheet, listOf(update(row, column, name, 1))).andReturn().response.status
            }
        }
        val statuses = pool.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        require(statuses.count { it == 200 } == 1) { "expected exactly one winner, got statuses $statuses" }
        require(statuses.count { it == 409 } == 1) { "expected exactly one conflict, got statuses $statuses" }
    }

    @Test
    fun `a cell that does not exist is a 404, not a 409`() {
        val user = register()
        val sheet = createSheet(user)
        addColumn(user, sheet, "A", "TEXT")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, java.util.UUID.randomUUID().toString(), "x", 1)))
            // A column that is not on this sheet fails validation before the
            // batch rule ever sees it, which is a 422 and a clearer message.
            .andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `a viewer cannot write cells`() {
        val owner = register()
        val viewer = register()
        val sheet = createSheet(owner)
        val column = addColumn(owner, sheet, "A", "TEXT")
        val row = addRow(owner, sheet)

        perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/sheets/$sheet/members"),
            owner.token,
            mapOf("email" to viewer.email, "role" to "VIEWER"),
        ).andExpect(status().isOk)

        batchUpdate(viewer, sheet, listOf(update(row, column, "nope", 1)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.requestId").exists())
    }

    @Test
    fun `a stranger sees 404 rather than 403, so sheet ids cannot be enumerated`() {
        val owner = register()
        val stranger = register()
        val sheet = createSheet(owner)
        val column = addColumn(owner, sheet, "A", "TEXT")
        val row = addRow(owner, sheet)

        batchUpdate(stranger, sheet, listOf(update(row, column, "nope", 1)))
            .andExpect(status().isNotFound)
    }
}
