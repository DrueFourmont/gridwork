package com.dfsystems.gridwork.api.web

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * An RFC 7807 problem+json body.
 *
 * `requestId` is not part of RFC 7807; it is an extension member, which the
 * spec explicitly allows. It is on every error without exception, so a user
 * reporting a failure can quote one string that finds the exact request in the
 * logs. That is the whole reason RequestIdFilter exists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val requestId: String?,
    val timestamp: Instant,
    /** Field level validation failures, when the problem is a bad body. */
    val errors: List<FieldProblem>? = null,
    /** Conflicting cells, when the problem is a version conflict. */
    val conflicts: List<CellConflictDetail>? = null,
) {
    data class FieldProblem(val field: String, val message: String)

    data class CellConflictDetail(
        val rowId: String,
        val columnId: String,
        val expectedVersion: Long,
        val actualVersion: Long,
        /** The value that is actually there now, so a UI can offer a merge without a refetch. */
        val actualValue: String?,
    )
}

@Component
class ProblemFactory {

    fun of(
        status: HttpStatus,
        detail: String,
        instance: String,
        errors: List<Problem.FieldProblem>? = null,
        conflicts: List<Problem.CellConflictDetail>? = null,
    ): Problem = Problem(
        // A stable, dereferenceable-looking URI per status. Clients switch on
        // this rather than on the human readable title, which is free to change.
        type = "https://gridwork.dfsystems.co/problems/${slug(status)}",
        title = status.reasonPhrase,
        status = status.value(),
        detail = detail,
        instance = instance,
        requestId = MDC.get(RequestIdFilter.MDC_KEY),
        timestamp = Instant.now(),
        errors = errors,
        conflicts = conflicts,
    )

    private fun slug(status: HttpStatus): String =
        status.reasonPhrase.lowercase().replace(" ", "-")
}
