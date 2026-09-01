# Handoff

State of Gridwork at the end of each phase. Updated by the phase that changed it.

## Current state

Phase 3 is done. Two API replicas now share live updates through Redis pub/sub,
browsers hold a websocket per open sheet, and a client that was disconnected
catches up by replaying `cell_history` rather than refetching the sheet.

Phase 2 is done. The web app now has a login screen, a sheet list, and a
virtualised grid with optimistic cell editing and a conflict merge prompt. It
is the first phase where the front end uses the API at all.

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

Phase 2 adds 33 web tests on top of the JVM suite. Totals: **97 JVM tests, 33
web unit tests, 9 Playwright tests, 0 failures.**

| Suite | Tests | What it covers |
|---|---|---|
| `writeQueue.test.ts` | 10 | coalescing, serialising, version learning, conflict adoption, rollback |
| `client.test.ts` | 8 | bearer token, 409 to ConflictError, 401 sign out, non JSON error bodies, schema rejection |
| `Grid.test.tsx` | 8 | only a window of rows in the DOM, editing, escape, clearing to null, read only |
| `ConflictDialog.test.tsx` | 7 | both values shown, resolution callbacks, empty values, alertdialog role |
| `grid.spec.ts` | 3 | edit persists across a reload, invalid value rolls back, sign out |
| `conflict.spec.ts` | 2 | real second writer produces a merge prompt; keep mine retries and wins |
| `performance.spec.ts` | 1 | frame timing plus the DOM stays small |
| `smoke.spec.ts` | 3 | app loads, proxy reaches the api, bad path is not a 500 |
| `ReplayRuleTest` | 10 | no cursor, cursor from the future, at and past the replay limit |
| `TwoReplicaRealtimeIT` | 4 | cross replica delivery, replay on reconnect, bad token, no access |
| `liveConnection.test.ts` | 10 | first frame auth, cursor tracking, backoff, resync, unknown frames |
| `realtime.spec.ts` | 3 | two browsers see each other, echo is a no op, replay after a real drop |

Phase 3 totals: **111 JVM tests, 43 web unit tests, 12 Playwright tests, 0
failures.**

Three worth naming:

- **`holds a second edit until the in flight request settles`** is the test for
  the subtle bug in ADR 0006: an edit racing an earlier edit of its own.
- **`renders only a window of rows, not all of them`** is the load bearing
  assertion for the 60 fps budget, and it needs no frame counter.
- **`keeping mine retries at the version the server reported and wins`** proves
  the client adopts `actualVersion` from a 409. Without it, resolving a
  conflict would conflict again forever.
- **`a write on replica one reaches a client connected to replica two`** starts
  a second, entirely separate Spring context on its own port, sharing only
  Postgres and Redis. A single context would pass with Redis removed
  altogether, because publisher and subscriber would be the same object in the
  same JVM, and the whole claim is that they are not.


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
| Swagger UI can actually authenticate | Authorize button present, bearerAuth scheme in the document, padlocks on protected operations, register and login exempt |

## Verified in a real browser

Phase 1: Swagger UI at `http://localhost:8080/swagger-ui.html`, opened in
Drue's own Chrome and screenshotted. All four tag groups render with all nine
operations, and the `PATCH /api/v1/sheets/{sheetId}/cells:batchUpdate` path
appears with its summary.

Phase 3: the two replica clip, in `docs/clips/`. Two browsers on two Vite
servers proxying to two different API containers, 8080 and 8081, sharing only
Postgres and Redis. `replica-one-writes.webm` shows the typing;
`replica-two-receives.webm` shows the text appearing in a browser that never
reloaded, with the live indicator green. A frame was extracted from the second
clip and inspected to confirm it shows "typed on replica one" rather than just
being a file of the right size.

Phase 2: the grid itself, opened on the 2,000 row seeded sheet in Drue's own
Chrome and screenshotted in three states.

| State | What the page showed | How it was produced |
|---|---|---|
| Loaded | 2,000 rows, 5 typed columns, 45 rows in the DOM, 64,000 px scroll height | `make seed` then open the sheet |
| Edited | cell went to "edited in Drue's Chrome", `data-version` 1 to 2 | double click, type, Enter |
| Conflicted | merge dialog with both values and "Nothing was overwritten" | a separate API call took the cell to version 3 first |

The third row is the one that matters, and it is the same method as Phase 0: a
screenshot of a working grid proves far less than a screenshot of the grid
correctly refusing to overwrite someone.

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

| Budget | Limit | Phase 0 | Phase 1 | Phase 2 | Verdict |
|---|---|---|---|---|---|
| api Docker image | 300 MB | 287.8 MB | 289.5 MB | unchanged | under, margin 10.5 MB |
| worker Docker image | 300 MB | 244.2 MB | unchanged | unchanged | under |
| web bundle, brotli | 250 kB | 68.0 kB | unchanged | 82.4 kB | under, 32.8 percent at Phase 3 (83.9 kB) |
| Grid scroll, 2,000 rows | 60 fps | not tested | not tested | 0 frames over budget | met |

**The 60 fps measurement, in full**, taken in a real Chromium over the
`make seed` fixture, scrolling the whole 64,000 pixel height:

```
scrollHeightPx     64000
framesSampled      299
medianMs           8.3
p95Ms              9.2
p99Ms              10.2
worstMs            10.6
framesOverBudget   0        (over 16.7 ms)
rowsInDom          37       (of 2000)
cellsInDom         185      (of 10000)
```

Read the median honestly: 8.3 ms is one frame at 120 Hz, which is this
machine's refresh rate. The grid is keeping up with the display rather than
being limited by anything in the code. On a 60 Hz screen the median would be
16.7 ms and the budget would still be met, but this number should not be quoted
as "120 fps of headroom".

The api image grew 1.7 MB, from adding
`spring-boot-starter-oauth2-resource-server`. That is a small change and a
worrying trend line: the margin is now under 4 percent of the budget and there
are five phases left. The fix when it comes will be a jlink runtime rather than
a full JRE, which is a bigger change than it sounds and should not be attempted
in a hurry. Watch this number every phase.

## Not verified by anyone

- Nobody has used the grid for real work. It has been driven by tests and by
  two scripted browser sessions, which is not the same as someone trying to get
  something done in it and finding out what is missing.
- The worker still consumes nothing. No SQS queue exists.
- Nothing has been tested with more than two replicas, and nothing has run for
  longer than a few minutes. Socket churn, memory growth in the subscription
  map, and Redis reconnection after an outage are all unobserved.
- No load test. The `PATCH cells:batchUpdate` p95 budget of 200 ms at 50 VUs is
  unmeasured, and so is what live fan-out costs it. Phase 4 brings k6.
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

**9. The bearer token lives in sessionStorage, readable by any script on the
page.** It survives a refresh and dies with the tab. localStorage would live
longer and in-memory would make the app unusable to demo. None of the three
defends against XSS; the correct answer is an httpOnly cookie, which needs CSRF
protection and a server change. A 15 minute expiry keeps the window small.
Chosen deliberately, written up in `web/src/state/authStore.ts`.

**10. There is no router, so a sheet has no shareable URL.** The open sheet is
remembered in sessionStorage, so a refresh does not throw you back to the list,
but you cannot send anyone a link to a sheet. Adding react-router is the fix
and it is a five minute change when deep links actually matter.

**11. The grid cannot delete or reorder anything.** No row delete, no column
delete, no rename, no drag to reorder. That is not a UI omission: the API has
no endpoints for any of it (Known issue 3). Reordering means rewriting the
positions of everything after the moved item, which is its own concurrency
problem.

**12. `make seed` writes to Postgres directly, bypassing the API.** It is a
test fixture and says so at the top of the file in capitals. 2,000 rows through
`POST /rows` would be 2,000 round trips and 2,000 transactions; the SQL takes
under half a second. The cost is that it proves nothing about the API, and it
must never be mistaken for a bulk import feature.

**13. The fps assertion in CI is deliberately loose.** `performance.spec.ts`
asserts a 50 ms p95, not the 16.7 ms the budget implies, because frame timing
on a shared CI runner is noisy and a flaky performance gate teaches people to
ignore red builds. The real number is measured locally and recorded above. If
that trade is wrong, the fix is a dedicated perf run, not a tighter threshold
on a noisy runner.

**15. Live updates make conflicts rare, so the 409 path is now the degraded
path.** A connected browser usually receives the other person's change before
the user types, so there is nothing to conflict with. The conflict e2e tests
now suppress the websocket on purpose to reproduce a conflict at all. That is
the feature working, but it means the most interesting code in the project is
no longer exercised by simply using the app, and it has to keep being tested
deliberately. See ADR 0007.

**16. Websocket origins are an allow list, not CORS.** A websocket handshake is
not covered by CORS, so nothing in the browser stops a page on any origin from
opening one. `gridwork.websocket.allowed-origins` is the only control, it has
no default in the prod profile, and getting it wrong silently accepts sockets
from anywhere.

**17. The subscription map is per replica and in memory, and nothing prunes it
under churn.** Sessions are removed on close, but a replica that has been up
for a long time with many short lived connections has not been observed. There
is no metric on socket count or subscription count yet.

**18. There is no presence.** You cannot see who else is viewing a sheet or
where their cursor is. `PLAN-SUMMARY.md` lists presence as a use for Redis, but
Phase 3's stated scope is fan-out and replay, so it was left out rather than
quietly added.

**19. The clip is committed as two 80 kB webm files.** They are build output in
a git repo, which is normally wrong. Kept because "clip recorded" is a Phase 3
deliverable and a link to a file that only exists on one laptop is not a
deliverable. Worth revisiting in Phase 7 when the Loom exists.

**14. React Compiler cannot memoise the Grid component.** `useVirtualizer`
returns fresh function identities each render, so the compiler skips the whole
component, which eslint reports as a warning. That warning is left in place
rather than silenced, because it is true, and it is why `GridCell` is wrapped
in `memo()` by hand. If that memo is ever removed, the 60 fps budget goes with
it.

**8. The compose stack runs with a committed JWT secret.** It is in
`docker-compose.yml` in plain text and named
`local-compose-only-secret-not-for-any-real-use`. That is correct for a local
stack and would be a serious problem anywhere else. Phase 6 supplies the real
one from AWS Secrets Manager and nothing about this file should be copied into
that path.
