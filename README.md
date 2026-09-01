# Gridwork

A small collaborative grid application. Sheets with typed columns and rows,
real-time multi-user editing across stateless API replicas, an automation
engine (trigger, condition, action) delivered through a transactional outbox
and an SQS worker, and an AI builder that drafts an automation from a plain
English sentence and asks a human to confirm it before it is saved.

Kotlin and Spring Boot on the back end, React and TypeScript on the front,
Postgres, Redis, and SQS underneath, deployed to AWS EKS with Terraform, Helm,
and GitHub Actions.

Built by Drue Fourmont at DF Systems, September 2026, as a deliberate exercise
in the parts of production software a solo builder rarely has to face: more
than one replica, more than one writer, and a queue that delivers twice.

## Status

| Phase | What | State |
|---|---|---|
| 0 | Scaffold: Gradle multi-project, Vite app, compose, CI | done |
| 1 | REST API: versioned cells, idempotency keys, cursor pagination, OpenAPI | done |
| 2 | Grid UI: virtualised, optimistic edits, conflict handling | done |
| 3 | Real-time: WebSockets, Redis fan-out across two replicas, replay | done |
| 4 | Automations: outbox relay, SQS, idempotent worker, DLQ, loop stop, load test | not started |
| 5 | AI builder: tool-use draft, validator warnings, evals gating main | not started |
| 6 | AWS: Terraform, EKS, Helm, OIDC CI/CD, zero-downtime restart | not started |
| 7 | Docs: README, HANDOFF, ADRs, Loom | not started |

## Links

- Live app: not yet deployed
- API docs (Swagger UI): `http://localhost:8080/swagger-ui.html` when running locally, not yet deployed
- Architecture: `docs/architecture.svg` (Phase 6)
- Walkthrough video: Phase 7
- Two replica live update clip: `docs/clips/`
- Plan: `docs/PLAN-SUMMARY.md`
- Decisions: `docs/adr/`
- Verification ledger: `docs/HANDOFF.md`

## How it works

Filled in as each phase lands. The short version of the design:

**Every cell has a version.** A write says which version it expects. If the
cell moved on, the write gets a 409 with the current value and nothing in the
batch is applied. No locks, no lost updates.

**Events leave through an outbox.** The cell change and the event describing
it are written in one database transaction. A relay publishes the outbox to
SQS. A missed live update can be recovered with a refetch, so real-time fan-out
is best effort over Redis pub/sub after commit; a missed automation event
cannot, so it goes through the outbox.

**API replicas are stateless.** Two run at all times. Each subscribes to Redis
for the sheets its sockets have open. Anything that must survive a pod restart
lives in Postgres, Redis, or SQS.

**The worker uses the same domain services as the API.** So versions, history,
permissions, and the outbox all apply to automation actions, and an automation
that triggers itself stops at depth three.

**The AI proposes; a human confirms.** The builder returns a structured draft
via tool use, the domain validator attaches warnings, and nothing is saved
until someone clicks. An eval fixture of 25 prompts gates merges to main.

## What is not here, and why

No offline mode, no CRDT, no formulas beyond same-row arithmetic, no file
attachments, no OAuth, no multi-region, no admin UI, no mobile layout. Each of
those is a real product feature and none of them teaches anything the project
does not already teach. Scope is a feature.

## Running locally

```
make up        # postgres, redis, localstack
make api       # spring boot on :8080
make web       # vite on :5173
make seed      # a 2,000 row sheet for the scroll budget, test fixture only
make test      # workflow lint, jvm build and tests, web checks
make down
```

Requires JDK 21, Node 22, Docker, and actionlint (`brew install actionlint`).

`make test` lints `.github/workflows/` first. A workflow file that does not
parse is rejected by GitHub before any job is scheduled, which produces a
failed run with no step log, so CI cannot catch that about itself. That check
has to happen here.
