package com.dfsystems.gridwork.api.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import jakarta.servlet.http.HttpServletRequest

/**
 * Turns everything that can go wrong into RFC 7807 problem+json.
 *
 * Nothing else in the application writes an error body. That is what keeps the
 * promise in CLAUDE.md that every error is problem+json and carries the request
 * id, rather than most errors being problem+json and Spring's defaults leaking
 * through on the paths nobody tested.
 */
@RestControllerAdvice
class ProblemHandler(private val problems: ProblemFactory) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(CellConflictException::class)
    fun onCellConflict(
        exception: CellConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<Problem> {
        val details = exception.conflicts.map { conflict ->
            Problem.CellConflictDetail(
                rowId = conflict.address.rowId.toString(),
                columnId = conflict.address.columnId.toString(),
                expectedVersion = conflict.expected.value,
                actualVersion = conflict.actual.value,
                actualValue = exception.currentValues[conflict.address],
            )
        }
        return respond(
            problems.of(
                status = HttpStatus.CONFLICT,
                detail = exception.message,
                instance = request.requestURI,
                conflicts = details,
            ),
        )
    }

    @ExceptionHandler(ApiException::class)
    fun onApiException(
        exception: ApiException,
        request: HttpServletRequest,
    ): ResponseEntity<Problem> = respond(
        problems.of(
            status = exception.status,
            detail = exception.message,
            instance = request.requestURI,
            errors = exception.errors,
        ),
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onValidationFailure(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<Problem> {
        val errors = exception.bindingResult.fieldErrors.map {
            Problem.FieldProblem(it.field, it.defaultMessage ?: "is invalid")
        }
        return respond(
            problems.of(
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                detail = "The request body failed validation.",
                instance = request.requestURI,
                errors = errors,
            ),
        )
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun onParameterValidationFailure(
        exception: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<Problem> {
        // Constraints on @RequestParam and @PathVariable, as opposed to on a
        // request body. Spring raises a different exception for these, and
        // without this handler they fell through to the catch-all and became a
        // 500. A limit of 5000 on a page is a client mistake, not a server one.
        val errors = exception.parameterValidationResults.flatMap { result ->
            result.resolvableErrors.map {
                Problem.FieldProblem(
                    field = result.methodParameter.parameterName ?: "parameter",
                    message = it.defaultMessage ?: "is invalid",
                )
            }
        }
        return respond(
            problems.of(
                status = HttpStatus.BAD_REQUEST,
                detail = "A query or path parameter is out of range.",
                instance = request.requestURI,
                errors = errors,
            ),
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadableBody(request: HttpServletRequest): ResponseEntity<Problem> = respond(
        problems.of(
            status = HttpStatus.BAD_REQUEST,
            // Deliberately does not echo the parser's message, which can
            // contain a fragment of the request body.
            detail = "The request body could not be read as JSON.",
            instance = request.requestURI,
        ),
    )

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun onMissingHeader(
        exception: MissingRequestHeaderException,
        request: HttpServletRequest,
    ): ResponseEntity<Problem> = respond(
        problems.of(
            status = HttpStatus.BAD_REQUEST,
            detail = "Required header '${exception.headerName}' is missing.",
            instance = request.requestURI,
        ),
    )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onTypeMismatch(
        exception: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<Problem> = respond(
        problems.of(
            status = HttpStatus.BAD_REQUEST,
            detail = "Path or query parameter '${exception.name}' is not in the expected format.",
            instance = request.requestURI,
        ),
    )

    @ExceptionHandler(NoHandlerFoundException::class, NoResourceFoundException::class)
    fun onNoHandler(request: HttpServletRequest): ResponseEntity<Problem> = respond(
        problems.of(HttpStatus.NOT_FOUND, "No endpoint matches this path.", request.requestURI),
    )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun onMethodNotSupported(request: HttpServletRequest): ResponseEntity<Problem> = respond(
        problems.of(
            HttpStatus.METHOD_NOT_ALLOWED,
            "That method is not supported on this path.",
            request.requestURI,
        ),
    )

    @ExceptionHandler(Exception::class)
    fun onAnythingElse(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<Problem> {
        // The stack trace goes to the log, where the request id ties it to this
        // response. It never goes to the client: an internal class name is an
        // invitation to probe, and it helps the caller not at all.
        log.error("unhandled exception on {} {}", request.method, request.requestURI, exception)
        return respond(
            problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Quote the request id if you report this.",
                request.requestURI,
            ),
        )
    }

    private fun respond(problem: Problem): ResponseEntity<Problem> =
        ResponseEntity.status(problem.status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
            .body(problem)
}
