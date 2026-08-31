package com.dfsystems.gridwork.api.web.dto

import com.dfsystems.gridwork.domain.ColumnType
import com.dfsystems.gridwork.domain.SheetRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

// ------------------------------------------------------------------ auth ----

@Schema(description = "Create an account.")
data class RegisterRequest(
    @field:NotBlank @field:Email
    val email: String,
    // 12 rather than the usual 8. Length is the only property of a password
    // that reliably survives contact with a real attacker.
    @field:NotBlank @field:Size(min = 12, max = 200, message = "must be between 12 and 200 characters")
    val password: String,
    @field:NotBlank @field:Size(max = 100)
    val displayName: String,
)

data class RegisterResponse(val userId: String, val email: String, val displayName: String)

@Schema(description = "Exchange email and password for a bearer token.")
data class LoginRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val password: String,
)

data class LoginResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
)

// ---------------------------------------------------------------- sheets ----

data class CreateSheetRequest(
    @field:NotBlank @field:Size(min = 1, max = 200) val name: String,
)

data class SheetResponse(
    val id: String,
    val name: String,
    val ownerId: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val columns: List<ColumnResponse>? = null,
)

data class AddMemberRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotNull val role: SheetRole,
)

data class MemberResponse(val userId: String, val role: SheetRole)

// --------------------------------------------------------------- columns ----

data class CreateColumnRequest(
    @field:NotBlank @field:Size(min = 1, max = 100) val name: String,
    @field:NotNull val type: ColumnType,
)

data class ColumnResponse(
    val id: String,
    val name: String,
    val type: ColumnType,
    val position: Int,
    val version: Long,
)

// ------------------------------------------------------------------ rows ----

data class RowResponse(
    val id: String,
    val position: Long,
    val version: Long,
    val cells: List<CellResponse>,
)

data class CellResponse(
    val columnId: String,
    val value: String?,
    val version: Long,
)

// ----------------------------------------------------------------- cells ----

@Schema(description = "A batch of cell writes. All or nothing: one conflict rejects the whole batch.")
data class BatchUpdateRequest(
    @field:NotNull @field:Size(min = 1, max = 500)
    val updates: List<CellUpdate>,
) {
    data class CellUpdate(
        @field:NotBlank val rowId: String,
        @field:NotBlank val columnId: String,
        @Schema(description = "The new value as a string. Null clears the cell.")
        val value: String?,
        @Schema(description = "The version the writer believes this cell is at. A mismatch is a 409.")
        val expectedVersion: Long,
    )
}

data class BatchUpdateResponse(val updated: List<UpdatedCell>) {
    data class UpdatedCell(
        val rowId: String,
        val columnId: String,
        val value: String?,
        val version: Long,
    )
}

// ------------------------------------------------------------ pagination ----

@Schema(description = "One page of results. nextCursor is null on the last page.")
data class Page<T>(
    val items: List<T>,
    @Schema(description = "Pass back as ?cursor= to get the next page. Opaque: do not construct one.")
    val nextCursor: String?,
)
