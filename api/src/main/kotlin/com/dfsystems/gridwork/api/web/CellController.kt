package com.dfsystems.gridwork.api.web

import com.dfsystems.gridwork.core.error.FieldError
import com.dfsystems.gridwork.core.error.UnprocessableException

import com.dfsystems.gridwork.api.security.userId
import com.dfsystems.gridwork.core.service.CellService
import com.dfsystems.gridwork.core.service.IdempotencyService
import com.dfsystems.gridwork.api.web.dto.BatchUpdateRequest
import com.dfsystems.gridwork.api.web.dto.BatchUpdateResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/sheets/{sheetId}")
@Tag(name = "Cells", description = "The versioned batch write that makes concurrent editing safe.")
class CellController(
    private val cells: CellService,
    private val idempotency: IdempotencyService,
    private val responses: IdempotentResponses,
) {

    /**
     * The colon in the path is deliberate. This is not a partial update of a
     * "cells" resource, it is a named action on the sheet, and the AIP style
     * `resource:verb` says so. It is also the path named in the budget table in
     * CLAUDE.md.
     */
    @PatchMapping("/cells:batchUpdate")
    @Operation(
        summary = "Write many cells at once, each conditional on the version the writer expected.",
        description = """
            All or nothing. If any cell has moved on since the caller read it,
            nothing is written and the response is a 409 listing every
            conflicting cell with its current value and version, so a client can
            show a merge without refetching the sheet.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Every cell was written."),
        ApiResponse(responseCode = "403", description = "Viewers cannot write cells."),
        ApiResponse(responseCode = "404", description = "A cell in the batch does not exist in this sheet."),
        ApiResponse(
            responseCode = "409",
            description = "At least one cell was modified by someone else. Nothing was applied.",
        ),
        ApiResponse(responseCode = "422", description = "A value is not valid for its column type."),
    )
    fun batchUpdate(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sheetId: UUID,
        @Valid @RequestBody request: BatchUpdateRequest,
        @RequestHeader(value = IdempotencyService.HEADER, required = false) idempotencyKey: String?,
    ): ResponseEntity<*> = responses.render(
        idempotency.execute(
            key = idempotencyKey,
            userId = jwt.userId(),
            method = "PATCH",
            path = "/api/v1/sheets/$sheetId/cells:batchUpdate",
            requestBody = request,
            successStatus = 200,
        ) {
            val applied = cells.batchUpdate(
                sheetId = com.dfsystems.gridwork.domain.SheetId(sheetId),
                actorId = jwt.userId(),
                requested = request.updates.map {
                    CellService.RequestedWrite(
                        rowId = parseUuid(it.rowId, "rowId"),
                        columnId = parseUuid(it.columnId, "columnId"),
                        value = it.value,
                        expectedVersion = it.expectedVersion,
                    )
                },
            )
            BatchUpdateResponse(
                updated = applied.map {
                    BatchUpdateResponse.UpdatedCell(
                        rowId = it.address.rowId.toString(),
                        columnId = it.address.columnId.toString(),
                        value = it.value,
                        version = it.version.value,
                    )
                },
            )
        },
    )

    private fun parseUuid(raw: String, field: String): UUID = try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw UnprocessableException(
            "One or more ids are not valid UUIDs.",
            listOf(FieldError(field, "is not a valid UUID")),
        )
    }
}
