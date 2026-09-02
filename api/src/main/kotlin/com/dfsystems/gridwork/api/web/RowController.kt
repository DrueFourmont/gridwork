package com.dfsystems.gridwork.api.web

import com.dfsystems.gridwork.api.security.userId
import com.dfsystems.gridwork.core.service.IdempotencyService
import com.dfsystems.gridwork.core.service.RowService
import com.dfsystems.gridwork.api.web.dto.CellResponse
import com.dfsystems.gridwork.api.web.dto.Page
import com.dfsystems.gridwork.api.web.dto.RowResponse
import com.dfsystems.gridwork.domain.RowId
import com.dfsystems.gridwork.domain.SheetId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/sheets/{sheetId}/rows")
@Tag(name = "Rows", description = "Rows and the cells they contain.")
class RowController(
    private val rows: RowService,
    private val idempotency: IdempotencyService,
    private val responses: IdempotentResponses,
) {

    @PostMapping
    @Operation(
        summary = "Append a row.",
        description = "The row is created with an empty cell in every column of the sheet.",
    )
    fun append(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sheetId: UUID,
        @RequestHeader(value = IdempotencyService.HEADER, required = false) idempotencyKey: String?,
    ): ResponseEntity<*> = responses.render(
        idempotency.execute(
            key = idempotencyKey,
            userId = jwt.userId(),
            method = "POST",
            path = "/api/v1/sheets/$sheetId/rows",
            requestBody = null,
            successStatus = 201,
        ) {
            val row = rows.append(SheetId(sheetId), jwt.userId())
            RowResponse(
                id = row.id.toString(),
                position = row.position,
                version = row.version,
                cells = emptyList(),
            )
        },
    )

    @GetMapping
    @Operation(
        summary = "One page of rows with their cells, in sheet order.",
        description = "Keyset paginated. Two queries per page regardless of how many rows come back.",
    )
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable sheetId: UUID,
        @RequestParam(required = false)
        @Parameter(description = "Opaque cursor from a previous page. Omit for the first page.")
        cursor: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) limit: Int,
    ): Page<RowResponse> {
        val page = rows.page(
            sheetId = SheetId(sheetId),
            actorId = jwt.userId(),
            cursorPosition = Cursor.decodeRow(cursor),
            limit = limit,
        )
        return Page(
            items = page.rows.map { row ->
                RowResponse(
                    id = row.id.toString(),
                    position = row.position,
                    version = row.version,
                    cells = page.cellsByRow[RowId(row.id)].orEmpty().map { cell ->
                        CellResponse(
                            columnId = cell.address.columnId.toString(),
                            value = cell.value,
                            version = cell.version.value,
                        )
                    },
                )
            },
            nextCursor = page.nextCursorPosition?.let { Cursor.encodeRow(it) },
        )
    }
}
