package com.dfsystems.gridwork.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The type of a column, which decides what its cells may contain. */
enum class ColumnType {
    TEXT,
    NUMBER,
    DATE,
    CHECKBOX,
    ;

    /**
     * Turn raw request input into a validated, canonical cell value.
     *
     * Canonical matters as much as valid. "1.50" and "1.5" are the same
     * number, and if they are stored as different strings then two equal cells
     * compare unequal, which breaks every later feature that asks whether a
     * cell changed.
     */
    fun parse(raw: String?): CellParse {
        if (raw.isNullOrEmpty()) return CellParse.Valid(CellValue.Empty)
        return when (this) {
            TEXT -> parseText(raw)
            NUMBER -> parseNumber(raw)
            DATE -> parseDate(raw)
            CHECKBOX -> parseCheckbox(raw)
        }
    }

    private fun parseText(raw: String): CellParse =
        if (raw.length > CellValue.MAX_TEXT_LENGTH) {
            CellParse.Invalid(CellProblem.TOO_LONG)
        } else {
            // Not trimmed. Trimming is a presentation choice, and silently
            // editing what someone typed is not the API's business.
            CellParse.Valid(CellValue.Text(raw))
        }

    private fun parseNumber(raw: String): CellParse {
        val parsed = try {
            BigDecimal(raw)
        } catch (_: NumberFormatException) {
            // BigDecimal also rejects "NaN" and "Infinity" this way, which is
            // what we want: those are Double concepts, not decimal ones.
            return CellParse.Invalid(CellProblem.NOT_A_NUMBER)
        }
        if (parsed.precision() > CellValue.MAX_NUMBER_DIGITS) {
            // Unbounded precision is a cheap denial of service: one request
            // with a million digits makes every later read of that sheet slow.
            return CellParse.Invalid(CellProblem.NUMBER_OUT_OF_RANGE)
        }
        return CellParse.Valid(CellValue.Number(parsed))
    }

    private fun parseDate(raw: String): CellParse = try {
        // LocalDate.parse is strict ISO-8601: it rejects "2026-8-31" for the
        // missing zero and "2026-02-30" for not existing.
        CellParse.Valid(CellValue.Date(LocalDate.parse(raw)))
    } catch (_: DateTimeParseException) {
        CellParse.Invalid(CellProblem.NOT_A_DATE)
    }

    private fun parseCheckbox(raw: String): CellParse = when (raw.lowercase()) {
        "true" -> CellParse.Valid(CellValue.Checkbox(true))
        "false" -> CellParse.Valid(CellValue.Checkbox(false))
        // "1", "yes", and "on" are deliberately refused. Accepting them looks
        // generous until someone moves a text column containing "1" to a
        // checkbox column and the data quietly changes meaning.
        else -> CellParse.Invalid(CellProblem.NOT_A_BOOLEAN)
    }
}

/** A validated cell value. Construction outside [ColumnType.parse] is possible but rarely right. */
sealed interface CellValue {

    /**
     * The canonical string written to the database, or null for an empty cell.
     * One representation of absent, so "is this cell blank" has one answer.
     */
    fun asStored(): String?

    data object Empty : CellValue {
        override fun asStored(): String? = null
    }

    data class Text(val value: String) : CellValue {
        override fun asStored(): String = value
    }

    data class Number(val value: BigDecimal) : CellValue {
        // stripTrailingZeros is what makes 1.50 and 1.5 the same value. The
        // toPlainString guards against it producing scientific notation, which
        // it does for values like 100 once the zeros are stripped.
        override fun asStored(): String = value.stripTrailingZeros().toPlainString()

        override fun equals(other: Any?): Boolean =
            other is Number && value.compareTo(other.value) == 0

        // BigDecimal.hashCode disagrees with compareTo for 1.50 versus 1.5, so
        // it cannot be used here without breaking the equals/hashCode contract.
        override fun hashCode(): Int = value.stripTrailingZeros().toPlainString().hashCode()
    }

    data class Date(val value: LocalDate) : CellValue {
        override fun asStored(): String = value.toString()
    }

    data class Checkbox(val value: Boolean) : CellValue {
        override fun asStored(): String = value.toString()
    }

    companion object {
        const val MAX_TEXT_LENGTH = 4_000
        const val MAX_NUMBER_DIGITS = 38
    }
}

/** Why a raw value was refused. Maps onto a field level error in the problem+json body. */
enum class CellProblem {
    TOO_LONG,
    NOT_A_NUMBER,
    NUMBER_OUT_OF_RANGE,
    NOT_A_DATE,
    NOT_A_BOOLEAN,
}

sealed interface CellParse {
    data class Valid(val value: CellValue) : CellParse
    data class Invalid(val problem: CellProblem) : CellParse
}
