package com.dfsystems.gridwork.api.web

import com.dfsystems.gridwork.api.security.userId
import com.dfsystems.gridwork.api.service.IdempotencyService
import com.dfsystems.gridwork.api.service.SheetService
import com.dfsystems.gridwork.api.web.dto.AddMemberRequest
import com.dfsystems.gridwork.api.web.dto.ColumnResponse
import com.dfsystems.gridwork.api.web.dto.CreateColumnRequest
import com.dfsystems.gridwork.api.web.dto.CreateSheetRequest
import com.dfsystems.gridwork.api.web.dto.MemberResponse
import com.dfsystems.gridwork.api.web.dto.Page
import com.dfsystems.gridwork.api.web.dto.SheetResponse
import com.dfsystems.gridwork.api.persistence.ColumnEntity
import com.dfsystems.gridwork.api.persistence.SheetEntity
import com.dfsystems.gridwork.domain.SheetId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/sheets")
@Tag(name = "Sheets", description = "Sheets, their members, and their columns.")
class SheetController(
    private val sheets: SheetService,
    private val idempotency: IdempotencyService,
    private val responses: IdempotentResponses,
) {

    @PostMapping
    @Operation(
        summary = "Create a sheet.",
        description = "Send an Idempotency-Key header to make a retry safe after a dropped connection.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created, or replayed from a previous identical request."),
        ApiResponse(responseCode = "409", description = "A request with this Idempotency-Key is still running."),
        ApiResponse(responseCode = "422", description = "This Idempotency-Key was used with a different body."),
    )
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateSheetRequest,
        @RequestHeader(value = IdempotencyService.HEADER, required = false)
        @Parameter(description = "Opaque client generated key. Retrying with the same key replays the first response.")
        idempotencyKey: String?,
    ): ResponseEntity<*> = responses.render(
        idempotency.execute(
            key = idempotencyKey,
            userId = jwt.userId(),
            method = "POST",
            path = "/api/v1/sheets",
            requestBody = request,
            successStatus = 201,
        ) { sheets.create(request.name, jwt.userId()).toResponse() },
    )

    @GetMapping
    @Operation(summary = "List the sheets you can see, newest first.")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false)
        @Parameter(description = "Opaque cursor from a previous page. Omit for the first page.")
        cursor: String?,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) limit: Int,
    ): Page<SheetResponse> {
        val decoded = Cursor.decodeSheet(cursor)
        val fetched = sheets.page(
            userId = jwt.userId(),
            cursor = decoded?.let { SheetService.SheetCursor(it.first, it.second) },
            limit = limit,
        )
        val hasMore = fetched.size > limit
        val page = if (hasMore) fetched.take(limit) else fetched
        return Page(
            items = page.map { it.toResponse() },
            nextCursor = page.lastOrNull()
                ?.takeIf { hasMore }
                ?.let { Cursor.encodeSheet(it.createdAt, it.id) },
        )
    }

    @GetMapping("/{sheetId}")
    @Operation(summary = "One sheet, with its columns.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found."),
        ApiResponse(responseCode = "404", description = "No such sheet, or you cannot see it."),
    )
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable sheetId: UUID): SheetResponse {
        val id = SheetId(sheetId)
        val sheet = sheets.get(id, jwt.userId())
        return sheet.toResponse(sheets.columnsOf(id).map { it.toResponse() })
    }

    @PostMapping("/{sheetId}/members")
    @Operation(summary = "Share this sheet with another user as editor or viewer.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Shared, or the existing role was changed."),
        ApiResponse(responseCode = "403", description = "Only the owner may share a sheet."),
    )
    fun addMember(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sheetId: UUID,
        @Valid @RequestBody request: AddMemberRequest,
    ): MemberResponse {
        val member = sheets.addMember(SheetId(sheetId), jwt.userId(), request.email, request.role)
        return MemberResponse(userId = member.userId.toString(), role = member.role)
    }

    @PostMapping("/{sheetId}/columns")
    @Operation(summary = "Add a typed column. Every existing row gains an empty cell in it.")
    fun addColumn(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sheetId: UUID,
        @Valid @RequestBody request: CreateColumnRequest,
        @RequestHeader(value = IdempotencyService.HEADER, required = false) idempotencyKey: String?,
    ): ResponseEntity<*> = responses.render(
        idempotency.execute(
            key = idempotencyKey,
            userId = jwt.userId(),
            method = "POST",
            path = "/api/v1/sheets/$sheetId/columns",
            requestBody = request,
            successStatus = 201,
        ) {
            sheets.addColumn(SheetId(sheetId), jwt.userId(), request.name, request.type).toResponse()
        },
    )
}

private fun SheetEntity.toResponse(columns: List<ColumnResponse>? = null) = SheetResponse(
    id = id.toString(),
    name = name,
    ownerId = ownerId.toString(),
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
    columns = columns,
)

internal fun ColumnEntity.toResponse() = ColumnResponse(
    id = id.toString(),
    name = name,
    type = type,
    position = position,
    version = version,
)
