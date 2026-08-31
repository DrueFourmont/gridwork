# Handoff

State of Gridwork at the end of each phase. Updated by the phase that changed it.

## Current state

Phase 1 is done. The API has authentication, sheets with members, typed
columns, rows, and the versioned batch cell write that ADR 0001 is about.
Errors are RFC 7807 throughout. Unsafe requests accept an idempotency key.
Lists are keyset paginated. Swagger UI documents the real contract.

Phase 1 was built without a go ahead, at Drue's explicit instruction ("do as
much as possible without my approval"), overriding the usual stop-and-report
rule in CLAUDE.md for this phase only. Read docs/PHASE-1-PLAN.md for what was
planned and which decisions were taken alone, then this file for what was
actually proven. That plan file can be deleted once it has been read.

Phase 0 remains as described below it. Its Known issue 1, the `permitAll`
scaffold, is now closed.

Pinned versions unchanged from Phase 0, plus
`spring-boot-starter-oauth2-resource-server` for JWT validation (ADR 0004).

## Verified by test

93 tests, 0 failures, 0 errors, 0 skipped, on a cold
`./gradlew clean build --no-build-cache --rerun-tasks`.

| Suite | Tests | What it covers |
|---|---|---|
| `CellValueTest` | 19 | every column type's accept and reject cases, and canonical storage |
| `BatchUpdateRuleTest` | 7 | all-or-nothing, all conflicts reported, duplicates, unknown cells |
| `VersionRuleTest` | 6 | match, stale, future, version zero refused, increment by one |
| `CellConflictIT` | 9 | the 409, live, including two concurrent writers racing one cell |
| `IdempotencyIT` | 9 | replay, differing body, per user scoping, concurrent double click |
| `PaginationIT` | 9 | page boundaries, insert while paging, membership scoping |
| `CellValidationIT` | 10 | type rules through the real endpoint, all errors at once |
| `AuthIT` | 10 | 401 shape, forged token, bcrypt at rest, no account enumeration |
| `ActuatorHealthIT` | 7 | boot against real Postgres, probes, every endpoint in the OpenAPI doc |
| `RequestIdFilterTest` | 5 | unchanged from Phase 0 |
| `WorkerContextTest` | 2 | unchanged from Phase 0 |

Three of these are worth naming, because they test things a unit test cannot:

- **`two concurrent writers at the same version, exactly one wins`** fires two
  real HTTP requests from two threads at one cell, both expecting version 1.
  Exactly one gets 200 and one gets 409. Nothing in the application serialises
  them; the `where version = :expected` in the UPDATE does.
- **`concurrent requests with one key create exactly one sheet`** fires four
  simultaneous creates with the same idempotency key and asserts one row in
  `sheets`.
- **`a row inserted while paging does not shift the pages`** is the test an
  offset implementation fails.

## Verified by direct inspection of the live system

Run against `make up` plus `make api`, on a database dropped and re-migrated
from scratch. Full request and response output is in the Phase 1 report.

| Claim | Evidence |
|---|---|
| Both migrations apply cleanly from an empty database | `flyway_schema_history` shows V1 baseline and V2 phase 1 core, both success = t |
| An unauthenticated request is 401 problem+json with a request id | `GET /api/v1/sheets` returned 401, `X-Request-Id` header, and a body with `requestId` |
| Register and login work end to end | user created, login returned an HS256 bearer token |
| An idempotent retry replays rather than repeats | second identical POST returned 201 with `Idempotent-Replay: true`, `select count(*) from sheets` returned 1 |
| The same key with a different body is refused | 422, "already used with a different request body" |
| A batch write bumps every cell from version 1 to 2 | three cells written in one request, all returned version 2 |
| A stale write is a 409 carrying the current value | 409 with `expectedVersion: 1`, `actualVersion: 2`, `actualValue: "Ship phase 1"` |
| One stale cell rolls back the whole batch | after a mixed batch was rejected, `Due` still read `2026-09-15` at version 2, so the valid cell in that batch was not written |
| Type validation runs against the column's type | `31/12/2026` into a DATE column returned 422 naming `updates[0].value` and `NOT_A_DATE` |
| Cursor pagination walks 25 rows in pages of 10 | pages of 10, 10, 5 with a null cursor on the last, 25 rows total, no repeats |
| Swagger UI loads and lists every endpoint | screenshotted in Drue's Chrome at `http://localhost:8080/swagger-ui.html` |

## Verified in a real browser

Phase 1: Swagger UI at `http://localhost:8080/swagger-ui.html`, opened in
Drue's own Chrome and screenshotted. All four tag groups render with all nine
operations, and the `PATCH /api/v1/sheets/{sheetId}/cells:batchUpdate` path
appears with its summary.

Phase 0: the app page at `http://localhost:5173`, screenshotted in three
states.

| State | What the page showed | How the state was forced |
|---|---|---|
| API up | `Gridwork` and `api: UP` | normal `make api` |
| API down | `Gridwork` and `api: unreachable` | `lsof -ti:8080 \| xargs kill` |
| API recovered | `Gridwork` and `api: UP` again | `make api` restarted |

The middle row is the one that matters: a screenshot of the healthy page alone
cannot distinguish a live health check from a hardcoded string.

## Verified by CI

Run 33361552735 on commit `cd81a77`: jvm 130s success, web 24s success,
playwright smoke 189s success. CI took two attempts; run 33358881662 failed in
zero seconds with no step log because `ci.yml` had a colon inside a plain YAML
scalar and GitHub could not parse the file. `make test` now runs `actionlint`
first, because a workflow that does not parse is the one failure CI cannot
report on itself.

Phase 1: run 33369282330 on commit `e72111c`, all three jobs green. jvm 135s,
web 23s, playwright smoke 201s.

## Budgets measured this phase

| Budget | Limit | Phase 0 | Phase 1 | Verdict |
|---|---|---|---|---|
| api Docker image | 300 MB | 287.8 MB | 289.5 MB | under, margin now 10.5 MB |
| worker Docker image | 300 MB | 244.2 MB | unchanged | under |
| web bundle, brotli | 250 kB | 68.0 kB | unchanged | under, web untouched this phase |

The api image grew 1.7 MB, from adding
`spring-boot-starter-oauth2-resource-server`. That is a small change and a
worrying trend line: the margin is now under 4 percent of the budget and there
are five phases left. The fix when it comes will be a jlink runtime rather than
a full JRE, which is a bigger change than it sounds and should not be attempted
in a hurry. Watch this number every phase.

## Not verified by anyone

- The web app does not use any of the Phase 1 API. It still calls only
  `/actuator/health`. The grid is Phase 2.
- No load test has been run. The `PATCH cells:batchUpdate` p95 budget of 200 ms
  at 50 VUs is unmeasured. Phase 4 brings k6.
- The largest sheet ever created in this system has 25 rows and 3 columns. The
  60 fps budget at 2,000 rows is untested and there is no seed script yet.
- The worker still consumes nothing. No SQS queue exists.
- Redis is running and healthy and the API still does not connect to it.
- Nothing is deployed anywhere.

## Known issues

**1. Idempotency keys are never deleted.** `idempotency_keys` grows forever.
It needs a scheduled delete of rows older than about 24 hours. There is an
index on `created_at` ready for it. Not urgent at this size, and wrong to leave
past Phase 6.

**2. A leaked JWT cannot be revoked.** Tokens are valid until they expire,
15 minutes. There is no revocation list and no token version column. Accepted
for a portfolio project, recorded here rather than hidden. See ADR 0004.

**3. Rows and columns append only.** There is no reordering, no delete, and no
rename. Reordering means rewriting the positions of everything after the moved
item, which is its own concurrency problem, and the grid in Phase 2 is where it
is actually needed.

**4. `updated_at` on `sheets` is never advanced.** Editing a cell does not
touch the parent sheet's timestamp, so "recently changed" ordering would be
wrong today. Nothing reads it yet.

**5. The batch endpoint reads every cell's version, then writes.** Two
statements rather than one. Correct, because the UPDATE re-checks the version
and a lost race becomes a 409, but it is two round trips on the hottest path.
Worth revisiting when the load test in Phase 4 gives a number to beat.

**6. The Phase 0 e2e CI job rebuilds the api image from scratch on every run.**
189s against 130s for jvm and 24s for web. Tolerable now.

**7. Local Node is 26, CI Node is 22.**

**8. The compose stack runs with a committed JWT secret.** It is in
`docker-compose.yml` in plain text and named
`local-compose-only-secret-not-for-any-real-use`. That is correct for a local
stack and would be a serious problem anywhere else. Phase 6 supplies the real
one from AWS Secrets Manager and nothing about this file should be copied into
that path.
