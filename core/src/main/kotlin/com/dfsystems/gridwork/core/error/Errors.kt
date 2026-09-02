package com.dfsystems.gridwork.core.error

import com.dfsystems.gridwork.domain.CellAddress
import com.dfsystems.gridwork.domain.CellConflict

/**
 * What kind of thing went wrong, in the application's own terms rather than
 * HTTP's.
 *
 * The services throw these, and api/ maps them to status codes. Core knows
 * nothing about HTTP on purpose: the worker uses these same services and has
 * no notion of a response code, so a service that threw an HttpStatus would be
 * describing a transport that is not always there.
 */
enum class ErrorKind {
    NOT_FOUND,
    FORBIDDEN,
    CONFLICT,
    UNPROCESSABLE,
    UNAUTHENTICATED,
}

/** A field level validation failure, for reporting several at once. */
data class FieldError(val field: String, val message: String)

open class ApiException(
    val kind: ErrorKind,
    override val message: String,
    val errors: List<FieldError>? = null,
) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(ErrorKind.NOT_FOUND, message)

class ForbiddenException(message: String) : ApiException(ErrorKind.FORBIDDEN, message)

class UnprocessableException(
    message: String,
    errors: List<FieldError>? = null,
) : ApiException(ErrorKind.UNPROCESSABLE, message, errors)

class ConflictException(message: String) : ApiException(ErrorKind.CONFLICT, message)

class UnauthenticatedException(message: String) : ApiException(ErrorKind.UNAUTHENTICATED, message)

/**
 * The versioned write conflict from ADR 0001. Carries every conflicting cell
 * with the value that is actually there, so a client can render a merge
 * without a second request.
 */
class CellConflictException(
    val conflicts: List<CellConflict>,
    val currentValues: Map<CellAddress, String?>,
) : ApiException(
    ErrorKind.CONFLICT,
    "${conflicts.size} cell(s) were modified by someone else. No changes were applied.",
)
