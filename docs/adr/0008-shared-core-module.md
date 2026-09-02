# ADR 0008: A shared core module, so the worker uses the API's own services

Date: 2026-09-02
Status: accepted

## Context

CLAUDE.md says the worker "performs automation actions THROUGH the same domain
services the API uses. It never writes to tables directly."

Until Phase 4 that was an aspiration rather than a fact. Every service lived in
`api/`, and the worker could not see any of it. The worker also must not have
`spring-boot-starter-web`, which was an explicit constraint from Phase 0, so it
could not simply depend on `api/` and inherit a servlet container.

## Decision

A new `core/` module holds the shared application layer: entities,
repositories, `CellService`, `SheetService`, `RowService`, `AccessService`,
`IdempotencyService`, the outbox, the automation engine's data access, and the
Redis publisher. Both `api/` and `worker/` depend on it.

`api/` keeps everything HTTP shaped: controllers, DTOs, problem+json, security,
the websocket handler, `AuthService`. `core/` has no web starter, so nothing
that depends on it gains one.

Service level errors moved to `core/error` and now carry an `ErrorKind` rather
than an `HttpStatus`. Only `ProblemHandler`, at the HTTP edge, maps a kind to a
status. A service throwing an `HttpStatus` would be describing a transport that
is not present when the worker calls it.

Both applications `@Import(CoreConfiguration::class)`, which does the component
scan, entity scan, and repository scan for the shared packages in one place
rather than in two lists that drift.

## Alternatives considered

- **Worker depends on `api`.** One line, and it drags spring-web, security, and
  springdoc into the worker image, violating a stated Phase 0 constraint and
  roughly doubling the worker's dependency surface for code it never runs.
- **Worker calls the API over HTTP.** A defensible architecture, and a
  different one: it means service tokens, another network hop per action, and
  the worker becoming a client of a thing it is deployed beside. It also does
  not match what CLAUDE.md describes.
- **Duplicate the write logic in the worker.** The version check, the history
  row, the outbox append, and the permission check would exist twice. The first
  time they diverged, an automation would silently do something a person could
  not, and nobody would notice until data was wrong.

## Consequences

Easier: the claim is now checkable, and there is a test for it. The worker
resolves `CellService` from the container, which means an automation's write
gets the same version check, history row, outbox event, and permission model as
a human edit. The integration test asserts a history row exists for an
automated write, which would be absent if the worker wrote to the table.

Harder: the refactor moved thirty three files and rewrote every import, which
is exactly the kind of change that is cheap now and expensive later. It also
means `core/` is on the critical path for both deployables, so a mistake there
breaks everything at once rather than one thing.

Also, the worker now needs a database. Its Phase 0 test asserted the opposite,
that it started with nothing, and that test had to be rewritten. Needing a
database is the point, not a regression.

## The one-minute spoken version

The worker and the API share a module holding the services, so an automation
writing a cell goes through exactly the same code a person does: same version
check, same history, same outbox, same permissions. The alternative is writing
that logic twice and hoping the copies stay in step, and they never do. The
worker deliberately does not get the web starter, so a shared module rather
than a dependency on the API itself, and errors in the shared layer carry a
semantic kind rather than an HTTP status, because the worker has no response to
put a status on.
