package com.dfsystems.gridwork.api.web

import com.dfsystems.gridwork.domain.CellConflict
import org.springframework.http.HttpStatus

/**
 * The one exception type the service layer throws. The advice below turns it
 * into problem+json, so no service ever has to know what an HTTP status is.
 */
open class ApiException(
    val status: HttpStatus,
    override val message: String,
    val errors: List<Problem.FieldProblem>? = null,
) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, message)

class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, message)

class UnprocessableException(
    message: String,
    errors: List<Problem.FieldProblem>? = null,
) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, errors)

class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, message)

/**
 * The 409 that ADR 0001 is about. Carries every conflicting cell with its
 * current value and version, so a client can render a merge without a refetch.
 */
class CellConflictException(
    val conflicts: List<CellConflict>,
    val currentValues: Map<com.dfsystems.gridwork.domain.CellAddress, String?>,
) : ApiException(
    HttpStatus.CONFLICT,
    "${conflicts.size} cell(s) were modified by someone else. No changes were applied.",
)
