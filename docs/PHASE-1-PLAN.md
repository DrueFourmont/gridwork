# Phase 1 plan: the REST API

Written 2026-08-31. Drue waived the usual "stop and wait for a go ahead" rule
for this phase, so this file exists in place of that conversation. Every
decision taken without approval is recorded here or in an ADR. Read this first,
then docs/HANDOFF.md for what was actually proven.

Phase 1 is done when, per docs/PLAN-SUMMARY.md: "409, idempotency, pagination
tests green; Swagger UI up".

## What gets built

**Domain, pure Kotlin, no Spring.** Value types, cell value validation per
column type, and the version rule. Tests first, exhaustive, no container.

**Schema, Flyway V2.** users, sheets, sheet_members, columns, rows, cells,
cell_history, idempotency_keys.

**Auth.** Email plus password, bcrypt, short lived HS256 JWT, refresh by
re-login. This replaces the `permitAll` scaffold from Phase 0, which is Known
issue 1 in HANDOFF.

**Endpoints.**

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/auth/register` | |
| POST | `/api/v1/auth/login` | returns a bearer token |
| POST | `/api/v1/sheets` | idempotent |
| GET | `/api/v1/sheets` | cursor paginated |
| GET | `/api/v1/sheets/{id}` | includes columns |
| POST | `/api/v1/sheets/{id}/members` | share as editor or viewer |
| POST | `/api/v1/sheets/{id}/columns` | typed |
| POST | `/api/v1/sheets/{id}/rows` | idempotent |
| GET | `/api/v1/sheets/{id}/rows` | cursor paginated, cells included |
| PATCH | `/api/v1/sheets/{id}/cells:batchUpdate` | versioned, 409 on conflict |

**Cross cutting.** RFC 7807 problem+json on every error, always carrying the
request id. Idempotency keys on every unsafe method. Cursor pagination.
OpenAPI annotations so Swagger UI documents the real contract.

## Decisions taken without approval

Each of these is a judgement call made while Drue was asleep. Any of them can
be reversed cheaply.

1. **Column types are TEXT, NUMBER, DATE, CHECKBOX.** Four is enough to make
   validation a real rule with real edge cases. Contact and picklist types are
   product surface, not new mechanics.
2. **Cell values are stored as text plus a type discriminator on the column,
   not as jsonb.** The column already knows the type, so jsonb would be storing
   the type twice and inviting them to disagree.
3. **The outbox table is NOT created in this phase.** CLAUDE.md describes the
   outbox as architecture, but the phase checklist puts the relay in Phase 4,
   and a table nothing writes to is a lie about how far along the system is.
4. **JWT validation uses Spring Security's resource server support** rather
   than a hand rolled filter. See ADR 0004. This is not OAuth login, which the
   guardrails forbid, it is the machinery for validating our own tokens.
5. **Idempotency is scoped to (user, method, path, key).** A replayed request
   returns the stored response. A different body under the same key is a 422,
   not a silent overwrite. See ADR 0003.
6. **Cursor pagination is keyset, not offset.** See ADR 0005.

## Out of scope, deliberately

No real time, no WebSockets, no Redis usage, no automations, no outbox, no AI,
no formulas, no undo. Sheets are created empty and rows are appended; there is
no row reordering or column reordering in this phase, because position
rewriting under concurrency is its own design problem and Phase 2 is where the
grid actually needs it.
