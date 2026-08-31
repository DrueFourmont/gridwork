# Gridwork

Collaborative grid application. Kotlin + Spring Boot API, React + TypeScript
frontend, Postgres, Redis, SQS, deployed to AWS EKS. Built as a portfolio piece
that mirrors a slice of Smartsheet: sheets, real-time editing, automations, and
an AI builder that drafts automations from plain English.

Owner: Drue Fourmont, DF Systems. Reads code fine, is learning Kotlin and AWS on
this project. Explain decisions in plain language, name the concept when you use
one (outbox, idempotency key, readiness probe), and keep summaries short.

## Working rules

- Every phase starts with an audit and a plan. STOP and report before changing
  files. Wait for a go-ahead.
- Tests first for anything with rules: cell value validation, the version rule,
  the automation evaluator, the loop rule, the replay protocol. Show the failing
  test before the implementation.
- Nothing is claimed as verified because it looked plausible. HANDOFF.md
  records what was verified by Drue in a browser, by a test, by inspection of
  the live system, or not at all.
- Nothing deploys from an uncommitted tree.
- Paste real command output when reporting. Not a summary of it.
- If something in the plan turns out to be wrong, say so and propose the change
  before making it. Then record it in an ADR.

## Style

- No em dashes anywhere. Not in prose, comments, README, commit messages, or
  log strings. Use commas, colons, or periods.
- Plain commit messages in lower case, one line, say what changed and why.
- British or American spelling, pick one per file and stay consistent.

## Architecture (see docs/adr/ for the reasoning)

- `domain/` has no Spring and no framework imports. Value types, the rule
  evaluator, the version rule. Pure Kotlin, exhaustively tested.
- `api/` is Spring Boot. Controllers are thin, services own rules and
  transactions, repositories own SQL. `@Transactional` lives on services.
- `worker/` consumes SQS and performs automation actions THROUGH the same domain
  services the API uses. It never writes to tables directly.
- Every mutable resource has a `version`. Writes carry an expected version.
  Conflicts are detected (409), never silently overwritten.
- Events leave the system through the `outbox_events` table, written in the
  same transaction as the data. A relay publishes to SQS. Redis pub/sub is used
  only for best-effort real-time fan-out after commit.
- API replicas are stateless. Anything that must survive a pod restart lives in
  Postgres, Redis, or SQS.
- Errors are RFC 7807 problem+json and always carry the request id.
- The AI builder proposes. A human confirms. The model never has write access.
- Secrets come from environment variables. Never in the repo, never in logs.

## Scope guardrails

Not in this project, do not add: offline mode, CRDT or OT, formulas beyond
same-row arithmetic, file attachments, OAuth or SSO, multi-region, an admin
UI, a mobile layout, tenants beyond user-owned sheets with members. If a
feature is not in docs/PLAN-SUMMARY.md, it does not exist.

## Budgets

These are limits, not targets. A change that breaks one needs a reason.

| Thing | Limit |
|---|---|
| api Docker image | 300 MB |
| web bundle, brotli, first load | 250 kB |
| `PATCH cells:batchUpdate` p95, local, 50 VUs | 200 ms |
| Grid scroll, 2,000 seeded rows | 60 fps |
| AI builder eval pass rate | 88 percent, gates main |
| AWS monthly cost while running | $150 |

## Commands

```
make up        # postgres, redis, localstack
make api       # ./gradlew :api:bootRun, local profile
make web       # vite dev server
make test      # everything
make down
```
