# Handoff

State of Gridwork at the end of each phase. Updated by the phase that changed it.

## Current state

Phase 0 is done. The repo now holds a Gradle multi-project (`domain`, `api`,
`worker`), a Vite React app in `web/`, a docker compose stack for Postgres,
Redis, LocalStack, and the API, multi-stage Dockerfiles for both JVM services,
and a GitHub Actions workflow.

No domain code exists yet. There are no sheets, no cells, no versions, no
automations, and no authentication. The API serves actuator and an OpenAPI
document and nothing else. That is the intended state at the end of a scaffold
phase.

Pinned versions: Gradle 8.14.5, Kotlin 2.4.10, Spring Boot 3.5.16, JDK 21,
Node 22 in CI, React 18, Vite 8, TypeScript 6. All JVM versions live in
`gradle/libs.versions.toml`.

CI took two attempts. Run 33358881662 failed in zero seconds with no step log:
`ci.yml` had `run: curl -isS -H 'X-Request-Id: ci-smoke' ...` as a plain YAML
scalar, and a plain scalar ends at a colon followed by a space, so GitHub could
not parse the file and scheduled nothing. Fixed with a block scalar in
`cd81a77`. `make test` now runs `actionlint` first, because a workflow that
does not parse is the one failure CI cannot report on itself.

## Verified by whom

**Verified by Drue in a browser.** Nothing yet. Phase 0 was run entirely
through the command line and a headless Chromium. Nobody has opened
`http://localhost:5173` by hand and looked at it. Worth two minutes before
Phase 1 starts: `make up`, `make api`, `make web`.

**Verified by test.**

| Claim | Test | Result |
|---|---|---|
| The domain module compiles and the test toolchain runs | `ModuleWiringTest` | 1 test, passed |
| A caller supplied `X-Request-Id` is kept and echoed | `RequestIdFilterTest` | passed |
| A missing or blank `X-Request-Id` yields a generated UUID | `RequestIdFilterTest` | passed |
| An oversized caller id is truncated to 128 characters | `RequestIdFilterTest` | passed |
| The request id is in the MDC during the request and cleared after | `RequestIdFilterTest` | passed |
| The app boots against a real Postgres, Flyway runs, Hibernate validates | `ActuatorHealthIT` (Testcontainers) | passed |
| `/actuator/health` returns UP | `ActuatorHealthIT` | passed |
| The readiness group exists and reports the database | `ActuatorHealthIT` | passed |
| The liveness group exists and reports UP | `ActuatorHealthIT` | passed |
| The request id filter is in the live filter chain, not just unit tested | `ActuatorHealthIT` | passed |
| The OpenAPI document is served and titled | `ActuatorHealthIT` | passed |
| The worker context starts with no web server and no database | `WorkerContextTest` | passed |
| The AWS SQS client is on the worker classpath | `WorkerContextTest` | passed |
| The app renders "Gridwork" | `App.test.tsx` (Vitest, RTL) | passed |
| The app shows the API status when the health call resolves | `App.test.tsx` | passed |
| The app reports the API unreachable when the call fails | `App.test.tsx` | passed |
| A real browser loads the built bundle and reads UP from the API | `e2e/smoke.spec.ts` (Playwright, Chromium) | passed |
| The API echoes a request id to a real HTTP client | `e2e/smoke.spec.ts` | passed |

Totals: 14 JVM tests, 3 web unit tests, 2 Playwright tests. Zero failures,
zero skipped. `./gradlew clean build --no-build-cache --rerun-tasks` is green.

**Verified by direct inspection of the live system.**

| Claim | How it was checked |
|---|---|
| All four compose services reach healthy | `docker compose up -d --wait` returned 0, `docker compose ps` shows healthy for api, postgres, redis, localstack |
| `/actuator/health` returns UP over real HTTP | `curl -i localhost:8080/actuator/health` returned 200 and `"status":"UP"` |
| A supplied request id is echoed on the response | `curl -H 'X-Request-Id: phase-0-check'` returned header `X-Request-Id: phase-0-check` |
| A generated request id differs per request | two bare curls returned two different UUIDs |
| Flyway actually ran | `select * from flyway_schema_history` shows V1 baseline, success = t |
| The request id reaches log lines, not just the header | with web logging at DEBUG, five log lines carried `[mdc-proof-12345]` |
| The prod profile emits one JSON object per line | ran with `SPRING_PROFILES_ACTIVE=prod`, log line parsed as JSON with `app`, `level`, `logger`, `thread`, `requestId`, `message` |
| No credential is written to the logs | `grep -ci 'generated security password'` on the api logs returned 0 |
| `server.shutdown=graceful` works | `docker stop --timeout 30` produced "Commencing graceful shutdown" then "Graceful shutdown complete" before exit |
| Prometheus metrics are exposed and tagged | `curl /actuator/prometheus` returns series tagged `application="gridwork-api"` |
| The runtime images contain their healthcheck tools | `curl 8.21.0` in the api image, BusyBox `pgrep` in the worker image |
| The Gradle wrapper is the genuine distribution | downloaded zip SHA-256 matched the published checksum, and `gradle-wrapper.properties` pins `distributionSha256Sum` |
| `make up` starts dependencies only | `docker compose ps` showed postgres, redis, localstack healthy and no api container |
| `make api` serves on 8080 with the local profile | log line "The following 1 profile is active: \"local\"", then curl returned UP and echoed `make-api-check` |
| `make web` serves on 5173 and proxies to the api | curl returned the app shell with `<title>Gridwork</title>`, and `/api/actuator/health` through the proxy returned UP and echoed `dev-proxy-check` |
| `make test` runs the jvm build and the web checks | ran green, output in the Phase 0 report |
| `make down` removes containers, network, and volumes | `docker compose ps` returned an empty table afterwards |
| CI is green on GitHub | run 33361552735 on commit `cd81a77`: jvm 130s success, web 24s success, playwright smoke 189s success |
| CI reaches a real API and the request id survives the round trip | the "show api health" step logged `HTTP/1.1 200` and `X-Request-Id: ci-smoke` |
| `make lint-ci` catches workflow yaml GitHub would reject | reintroduced the exact bug from run 33358881662, `make test` stopped on it before the gradle build |

**Budgets measured this phase.**

| Budget | Limit | Measured | Verdict |
|---|---|---|---|
| api Docker image | 300 MB | 287.8 MB uncompressed, 139.6 MB compressed | under |
| worker Docker image | 300 MB | 244.2 MB uncompressed, 101.4 MB compressed | under |
| web bundle, brotli, first load | 250 kB | 68.0 kB (js 66.1, css 1.7, html 0.2) | under |

The api image was first built on `eclipse-temurin:21-jre` and came out at
435.5 MB uncompressed, over budget. It was rebuilt on
`eclipse-temurin:21-jre-alpine`, which fits. No ADR was needed because the
first fallback worked. Both Dockerfiles carry a comment recording the measured
reason.

**Not verified by anyone.**

- The worker has never consumed a message. It boots, and that is all that was
  claimed.
- LocalStack reports SQS available but no queue has been created and nothing
  has been published to one.
- Redis is running and healthy but the API does not connect to it. There is no
  Redis dependency on the api classpath yet.
- Nothing has been deployed anywhere. There is no AWS account activity.
- No human has looked at the rendered page. Chromium asserted the text, which
  is not the same as someone seeing that it is not broken.

## Known issues

**1. The API permits every request. This is deliberate scaffold wiring, and
Phase 1 must replace it.**

`api/src/main/kotlin/com/dfsystems/gridwork/api/config/SecurityConfig.kt`
configures `anyRequest().permitAll()`. It carries a `TODO(Phase 1)` comment
saying the same thing. It is currently harmless because the only endpoints
that exist are actuator and the OpenAPI document, but it is not a security
policy and must not reach any public deployment.

Phase 1 replaces it with a JWT bearer token filter, `authenticated()` as the
default for every path, an explicit permit list for the health endpoints, the
OpenAPI document, the Swagger UI, and the login endpoint, and RFC 7807
problem+json for 401 and 403 carrying the request id.

`UserDetailsServiceAutoConfiguration` is excluded in
`GridworkApiApplication.kt` so Spring Boot stops printing a generated password
to standard out. Phase 1 supplies a real `UserDetailsService` backed by
Postgres and removes the exclusion.

**2. Two dependencies are installed but unused.** `@tanstack/react-virtual`
and `zustand` are in `web/package.json` and referenced by no code. They were
installed now so Phase 2 does not have to stop and add them. They are tree
shaken out of the bundle, which the 68 kB brotli measurement confirms.

**3. The worker healthcheck only asks whether the JVM is alive.** There is no
queue consumer to observe yet. Phase 4 should replace the `pgrep` check with
something that reflects whether the worker is actually polling, most likely a
last-poll timestamp behind a small actuator surface.

**4. The e2e CI job rebuilds the api image from scratch on every run.** It has
no Docker layer cache, so it is the slowest job: 189s against 130s for jvm and
24s for web. That is tolerable now and will not stay tolerable as the API
grows. When it stops being acceptable, wire up buildx with the GitHub Actions
cache backend, or build the jar in the `jvm` job and ship it to a runtime-only
Dockerfile stage.

**5. Local Node is 26, CI Node is 22.** README says Node 22 and CI matches it.
The local machine runs 26 and the build works there too, but the two are not
the same and only 22 is what CI proves.
