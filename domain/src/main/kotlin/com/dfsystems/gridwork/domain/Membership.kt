package com.dfsystems.gridwork.domain

/**
 * What a user may do with a sheet. The whole tenancy model, per the scope
 * guardrails: a user owns sheets and shares them with other users.
 */
enum class SheetRole {
    OWNER,
    EDITOR,
    VIEWER,
    ;

    fun canRead(): Boolean = true

    fun canWriteCells(): Boolean = this == OWNER || this == EDITOR

    /** Structure means columns, rows, and the sheet itself. Sharing is owner only. */
    fun canChangeStructure(): Boolean = this == OWNER || this == EDITOR

    fun canShare(): Boolean = this == OWNER
}
