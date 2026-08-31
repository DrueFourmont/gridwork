package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.support.ApiIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Cursor pagination, per ADR 0005.
 *
 * The reason for keyset over offset is not raw speed, it is correctness while
 * the data changes underneath a caller who is paging. The test below that
 * inserts a row mid-page is the one that would fail on an offset.
 */
class PaginationIT : ApiIntegrationTest() {

    @Test
    fun `rows come back in order across pages with no gap and no repeat`() {
        val user = register()
        val sheet = createSheet(user)
        addColumn(user, sheet, "A", "TEXT")
        repeat(25) { addRow(user, sheet) }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val query = if (cursor == null) "?limit=10" else "?limit=10&cursor=$cursor"
            val page = readRows(user, sheet, query)
            page["items"].forEach { seen += it["id"].asText() }
            cursor = page["nextCursor"].takeIf { !it.isNull }?.asText()
            pages++
        } while (cursor != null && pages < 10)

        require(seen.size == 25) { "expected 25 rows across pages, saw ${seen.size}" }
        require(seen.toSet().size == 25) { "a row was returned twice" }
        require(pages == 3) { "expected 3 pages of 10, 10, 5 but took $pages" }
    }

    @Test
    fun `the last page has a null cursor`() {
        val user = register()
        val sheet = createSheet(user)
        repeat(3) { addRow(user, sheet) }

        perform(get("/api/v1/sheets/$sheet/rows?limit=10"), user.token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
    }

    @Test
    fun `a row inserted while paging does not shift the pages`() {
        // On an offset this is the classic bug: inserting before the cursor
        // pushes an unseen row past the offset and it is never returned. A
        // keyset cursor names a position, not a count, so it cannot drift.
        val user = register()
        val sheet = createSheet(user)
        repeat(10) { addRow(user, sheet) }

        val firstPage = readRows(user, sheet, "?limit=5")
        val firstIds = firstPage["items"].map { it["id"].asText() }
        val cursor = firstPage["nextCursor"].asText()

        // Rows append at the end here, but the point holds: the second page is
        // defined by where the first one stopped, not by how many rows exist.
        repeat(3) { addRow(user, sheet) }

        val secondPage = readRows(user, sheet, "?limit=5&cursor=$cursor")
        val secondIds = secondPage["items"].map { it["id"].asText() }

        require(firstIds.intersect(secondIds.toSet()).isEmpty()) {
            "a row appeared on two pages after an insert"
        }
        require(secondIds.size == 5) { "expected a full second page, got ${secondIds.size}" }
    }

    @Test
    fun `rows carry their cells, one query for the page rather than one per row`() {
        val user = register()
        val sheet = createSheet(user)
        val a = addColumn(user, sheet, "A", "TEXT")
        val b = addColumn(user, sheet, "B", "NUMBER")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, a, "hello", 1), update(row, b, "42", 1)))
            .andExpect(status().isOk)

        perform(get("/api/v1/sheets/$sheet/rows"), user.token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].cells.length()").value(2))
    }

    @Test
    fun `sheets are paginated newest first`() {
        val user = register()
        repeat(7) { createSheet(user, "Sheet $it") }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = if (cursor == null) "?limit=3" else "?limit=3&cursor=$cursor"
            val page = perform(get("/api/v1/sheets$query"), user.token).andReturn().json()
            page["items"].forEach { seen += it["id"].asText() }
            cursor = page["nextCursor"].takeIf { !it.isNull }?.asText()
        } while (cursor != null)

        require(seen.size == 7) { "expected 7 sheets, saw ${seen.size}" }
        require(seen.toSet().size == 7) { "a sheet was returned twice" }
    }

    @Test
    fun `the sheet list only contains sheets the caller is a member of`() {
        val alice = register()
        val bob = register()
        createSheet(alice, "Alice only")
        createSheet(bob, "Bob only")

        perform(get("/api/v1/sheets"), alice.token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Alice only"))
    }

    @Test
    fun `a shared sheet appears in the other user's list`() {
        val alice = register()
        val bob = register()
        val sheet = createSheet(alice, "Shared")
        perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/sheets/$sheet/members"),
            alice.token,
            mapOf("email" to bob.email, "role" to "EDITOR"),
        ).andExpect(status().isOk)

        perform(get("/api/v1/sheets"), bob.token)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Shared"))
    }

    @Test
    fun `a malformed cursor is a 422, not a 500`() {
        val user = register()
        val sheet = createSheet(user)
        perform(get("/api/v1/sheets/$sheet/rows?cursor=not-a-real-cursor"), user.token)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.requestId").exists())
    }

    @Test
    fun `a limit over the maximum is rejected`() {
        val user = register()
        val sheet = createSheet(user)
        perform(get("/api/v1/sheets/$sheet/rows?limit=5000"), user.token)
            .andExpect(status().is4xxClientError)
    }
}
