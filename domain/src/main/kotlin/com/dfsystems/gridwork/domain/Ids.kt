package com.dfsystems.gridwork.domain

import java.util.UUID

/**
 * Identifiers are wrapped rather than passed around as raw UUIDs. It costs
 * nothing at runtime, since a value class compiles to the underlying UUID, and
 * it makes it impossible to pass a RowId where a ColumnId belongs. In a
 * codebase where a cell is addressed by two ids of the same shape, that is not
 * a hypothetical mistake.
 */
@JvmInline
value class UserId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class SheetId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ColumnId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class RowId(val value: UUID) {
    override fun toString(): String = value.toString()
}

/** A cell is addressed by its row and its column. There is no cell id. */
data class CellAddress(val rowId: RowId, val columnId: ColumnId)
