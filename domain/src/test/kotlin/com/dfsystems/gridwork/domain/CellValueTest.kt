package com.dfsystems.gridwork.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test

/**
 * What a cell is allowed to contain, given the type of its column.
 *
 * This is the first rule in the system that can reject a user's input, so it
 * is worth being exact about. Two things matter: what is accepted, and what
 * the accepted value is normalised to before it is stored. Storing "1.50" and
 * "1.5" as different strings would make two equal numbers look unequal.
 */
class CellValueTest {

    @Test
    fun `text accepts any string`() {
        val result = ColumnType.TEXT.parse("hello")
        result.shouldBeInstanceOf<CellParse.Valid>()
        result.value shouldBe CellValue.Text("hello")
    }

    @Test
    fun `text preserves leading and trailing spaces`() {
        // Trimming is a presentation choice. The API should not silently edit
        // what someone typed.
        val result = ColumnType.TEXT.parse("  padded  ")
        result.shouldBeInstanceOf<CellParse.Valid>()
        result.value shouldBe CellValue.Text("  padded  ")
    }

    @Test
    fun `text rejects a value over the length limit`() {
        val result = ColumnType.TEXT.parse("x".repeat(CellValue.MAX_TEXT_LENGTH + 1))
        result.shouldBeInstanceOf<CellParse.Invalid>()
        result.problem shouldBe CellProblem.TOO_LONG
    }

    @Test
    fun `text accepts a value exactly at the length limit`() {
        ColumnType.TEXT.parse("x".repeat(CellValue.MAX_TEXT_LENGTH))
            .shouldBeInstanceOf<CellParse.Valid>()
    }

    @Test
    fun `null is empty for every column type`() {
        ColumnType.entries.forEach { type ->
            val result = type.parse(null)
            result.shouldBeInstanceOf<CellParse.Valid>()
            result.value shouldBe CellValue.Empty
        }
    }

    @Test
    fun `an empty string is empty for every column type`() {
        ColumnType.entries.forEach { type ->
            val result = type.parse("")
            result.shouldBeInstanceOf<CellParse.Valid>()
            result.value shouldBe CellValue.Empty
        }
    }

    @Test
    fun `number accepts an integer`() {
        val result = ColumnType.NUMBER.parse("42")
        result.shouldBeInstanceOf<CellParse.Valid>()
        result.value shouldBe CellValue.Number(BigDecimal("42"))
    }

    @Test
    fun `number accepts a negative decimal`() {
        val result = ColumnType.NUMBER.parse("-3.25")
        result.shouldBeInstanceOf<CellParse.Valid>()
        result.value shouldBe CellValue.Number(BigDecimal("-3.25"))
    }

    @Test
    fun `number normalises trailing zeros so equal numbers compare equal`() {
        val a = ColumnType.NUMBER.parse("1.50")
        val b = ColumnType.NUMBER.parse("1.5")
        a.shouldBeInstanceOf<CellParse.Valid>()
        b.shouldBeInstanceOf<CellParse.Valid>()
        a.value shouldBe b.value
        a.value.asStored() shouldBe "1.5"
    }

    @Test
    fun `number rejects words`() {
        val result = ColumnType.NUMBER.parse("twelve")
        result.shouldBeInstanceOf<CellParse.Invalid>()
        result.problem shouldBe CellProblem.NOT_A_NUMBER
    }

    @Test
    fun `number rejects infinity and not-a-number`() {
        listOf("Infinity", "-Infinity", "NaN").forEach { raw ->
            val result = ColumnType.NUMBER.parse(raw)
            result.shouldBeInstanceOf<CellParse.Invalid>()
            result.problem shouldBe CellProblem.NOT_A_NUMBER
        }
    }

    @Test
    fun `number rejects a value with too many digits`() {
        // Unbounded precision is a denial of service vector: someone can post a
        // million digit number and make every later read expensive.
        val result = ColumnType.NUMBER.parse("9".repeat(CellValue.MAX_NUMBER_DIGITS + 1))
        result.shouldBeInstanceOf<CellParse.Invalid>()
        result.problem shouldBe CellProblem.NUMBER_OUT_OF_RANGE
    }

    @Test
    fun `date accepts an iso date`() {
        val result = ColumnType.DATE.parse("2026-08-31")
        result.shouldBeInstanceOf<CellParse.Valid>()
        result.value shouldBe CellValue.Date(LocalDate.of(2026, 8, 31))
    }

    @Test
    fun `date rejects a non iso format`() {
        listOf("31-08-2026", "08/31/2026", "2026-8-31").forEach { raw ->
            val result = ColumnType.DATE.parse(raw)
            result.shouldBeInstanceOf<CellParse.Invalid>()
            result.problem shouldBe CellProblem.NOT_A_DATE
        }
    }

    @Test
    fun `date rejects a day that does not exist`() {
        val result = ColumnType.DATE.parse("2026-02-30")
        result.shouldBeInstanceOf<CellParse.Invalid>()
        result.problem shouldBe CellProblem.NOT_A_DATE
    }

    @Test
    fun `checkbox accepts true and false in any case`() {
        listOf("true", "TRUE", "True").forEach { raw ->
            val result = ColumnType.CHECKBOX.parse(raw)
            result.shouldBeInstanceOf<CellParse.Valid>()
            result.value shouldBe CellValue.Checkbox(true)
        }
        listOf("false", "FALSE", "False").forEach { raw ->
            val result = ColumnType.CHECKBOX.parse(raw)
            result.shouldBeInstanceOf<CellParse.Valid>()
            result.value shouldBe CellValue.Checkbox(false)
        }
    }

    @Test
    fun `checkbox rejects yes and one`() {
        // Accepting "1" and "yes" looks friendly and creates ambiguity the
        // moment someone stores the string "1" in a text column and moves it.
        listOf("yes", "no", "1", "0", "on").forEach { raw ->
            val result = ColumnType.CHECKBOX.parse(raw)
            result.shouldBeInstanceOf<CellParse.Invalid>()
            result.problem shouldBe CellProblem.NOT_A_BOOLEAN
        }
    }

    @Test
    fun `stored form round trips through parse for every type`() {
        val samples = listOf(
            ColumnType.TEXT to "some text",
            ColumnType.NUMBER to "12.5",
            ColumnType.DATE to "2026-01-02",
            ColumnType.CHECKBOX to "true",
        )
        samples.forEach { (type, raw) ->
            val first = type.parse(raw)
            first.shouldBeInstanceOf<CellParse.Valid>()
            val second = type.parse(first.value.asStored())
            second.shouldBeInstanceOf<CellParse.Valid>()
            second.value shouldBe first.value
        }
    }

    @Test
    fun `empty stores as null rather than an empty string`() {
        // One representation of absent. Otherwise "is this cell blank" has two
        // answers and every query has to check for both.
        CellValue.Empty.asStored() shouldBe null
    }
}
