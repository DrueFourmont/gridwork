package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.support.ApiIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The domain's cell rules, exercised through the real endpoint.
 *
 * The rules themselves are unit tested in the domain module. This proves they
 * are actually wired to the column's type at the point a request arrives, which
 * a unit test cannot tell you.
 */
class CellValidationIT : ApiIntegrationTest() {

    @Test
    fun `a number column refuses text and names the offending field`() {
        val user = register()
        val sheet = createSheet(user)
        val amount = addColumn(user, sheet, "Amount", "NUMBER")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, amount, "twelve", 1)))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errors[0].field").value("updates[0].value"))
            .andExpect(jsonPath("$.errors[0].message").value(
                org.hamcrest.Matchers.containsString("NOT_A_NUMBER")))
    }

    @Test
    fun `a number is normalised on the way in`() {
        val user = register()
        val sheet = createSheet(user)
        val amount = addColumn(user, sheet, "Amount", "NUMBER")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, amount, "1.50", 1)))
            .andExpect(status().isOk)
            // Stored as 1.5, so two cells holding the same number are equal as
            // strings too. Phase 4's automation conditions depend on this.
            .andExpect(jsonPath("$.updated[0].value").value("1.5"))
    }

    @Test
    fun `a date column refuses a non iso date`() {
        val user = register()
        val sheet = createSheet(user)
        val due = addColumn(user, sheet, "Due", "DATE")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, due, "31/08/2026", 1)))
            .andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `a checkbox column refuses yes`() {
        val user = register()
        val sheet = createSheet(user)
        val done = addColumn(user, sheet, "Done", "CHECKBOX")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, done, "yes", 1)))
            .andExpect(status().isUnprocessableEntity)
        batchUpdate(user, sheet, listOf(update(row, done, "true", 1)))
            .andExpect(status().isOk)
    }

    @Test
    fun `every invalid cell in a batch is reported at once`() {
        // Reporting only the first would make a client fix a fifty cell paste
        // one round trip at a time.
        val user = register()
        val sheet = createSheet(user)
        val amount = addColumn(user, sheet, "Amount", "NUMBER")
        val due = addColumn(user, sheet, "Due", "DATE")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(
            update(row, amount, "not a number", 1),
            update(row, due, "not a date", 1),
        ))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errors.length()").value(2))
    }

    @Test
    fun `null clears a cell and the cell keeps its version sequence`() {
        val user = register()
        val sheet = createSheet(user)
        val note = addColumn(user, sheet, "Note", "TEXT")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(update(row, note, "something", 1))).andExpect(status().isOk)
        batchUpdate(user, sheet, listOf(update(row, note, null, 2)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updated[0].value").doesNotExist())
            .andExpect(jsonPath("$.updated[0].version").value(3))
    }

    @Test
    fun `the same cell twice in one batch is refused as ambiguous`() {
        val user = register()
        val sheet = createSheet(user)
        val note = addColumn(user, sheet, "Note", "TEXT")
        val row = addRow(user, sheet)

        batchUpdate(user, sheet, listOf(
            update(row, note, "first", 1),
            update(row, note, "second", 1),
        )).andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `an empty batch is refused`() {
        val user = register()
        val sheet = createSheet(user)
        batchUpdate(user, sheet, emptyList()).andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `a column added later gives every existing row an empty cell in it`() {
        val user = register()
        val sheet = createSheet(user)
        addColumn(user, sheet, "First", "TEXT")
        val row = addRow(user, sheet)
        val second = addColumn(user, sheet, "Second", "TEXT")

        // The cell has to exist for the write to be a plain versioned UPDATE
        // rather than an upsert. This is what keeps the hot path simple.
        batchUpdate(user, sheet, listOf(update(row, second, "works", 1)))
            .andExpect(status().isOk)
    }

    @Test
    fun `a duplicate column name is refused`() {
        val user = register()
        val sheet = createSheet(user)
        addColumn(user, sheet, "Name", "TEXT")
        perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/sheets/$sheet/columns"),
            user.token,
            mapOf("name" to "NAME", "type" to "TEXT"),
        ).andExpect(status().isUnprocessableEntity)
    }
}
