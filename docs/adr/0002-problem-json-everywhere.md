# ADR 0002: Every error is problem+json carrying the request id

Date: 2026-08-31
Status: accepted

## Context

An API that returns a JSON body on success and a Spring whitelabel page, or an
empty body with a WWW-Authenticate header, on failure is two APIs. A client has
to parse one shape when things work and guess at another when they do not. It
is also the failure paths that a user reports, and those are exactly the ones
with no information in them by default.

Phase 0 promised in CLAUDE.md that errors would be RFC 7807 and would carry the
request id. Phase 1 is the first phase with errors worth returning.

## Decision

One `@RestControllerAdvice` produces every error body in the application, and
nothing else writes one. Services throw `ApiException` subtypes that name a
status and a message and know nothing about HTTP. Spring Security's entry point
and access denied handler are overridden to go through the same factory, because
otherwise 401 and 403 escape as empty bodies.

Every body carries `requestId`, taken from the MDC that `RequestIdFilter` set.
That is an RFC 7807 extension member, which the spec allows. `type` is a stable
URI per status so clients switch on it rather than on the human readable title.

A 409 from a cell conflict carries a `conflicts` array with each cell's expected
version, actual version, and current value, so a UI can render a merge without a
second request. A 422 carries an `errors` array naming each offending field.

## Alternatives considered

- Spring's built in `ProblemDetail`: close, but it does not cover the security
  filter chain, so 401 and 403 would still have been the odd ones out.
- Per controller error handling: guarantees that the one endpoint nobody thought
  about returns something different.
- A bare `{"error": "..."}` shape: fewer characters, no standard, and no place
  to hang the conflict list that ADR 0001 requires.

## Consequences

Easier: one place to change the error contract, and the request id is impossible
to forget. A client can write one error parser.

Harder: the advice has to enumerate Spring's exception types, and the ones it
misses become 500s. `HandlerMethodValidationException` was exactly that during
this phase: a query parameter out of range returned 500 until it was handled.
Every new failure mode needs a test asserting its status, or the catch-all
quietly swallows it.

## The one-minute spoken version

Every error in the API is the same JSON shape, the one RFC 7807 defines, and
every one of them carries the request id that is also on the response header and
in the log lines. So when someone says "it broke", they paste one string and I
can find the exact request. Conflicts carry the current value of every cell that
conflicted, so the UI can show a merge without asking again.
