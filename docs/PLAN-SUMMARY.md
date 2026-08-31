# Gridwork: plan summary

Condensed from the execution plan kept outside this repo. Claude Code reads this file at the start of every phase. If the plan and this file disagree, this file is wrong: fix it.

# THE ONE-PAGE STRATEGY

**What it is:** Gridwork is a small collaborative grid application. Sheets with typed columns and rows, real-time multi-user editing, an automation engine (trigger, condition, action, same model as Smartsheet's), and an AI builder that turns a plain-English sentence into an automation and asks for confirmation before saving it.

**Why this and not something else:** The posting lists Kotlin, Java, TypeScript, React, AWS, Kubernetes, distributed systems, REST APIs, and AI-driven development. A collaborative grid with automations is the one project that needs all of them for real reasons, not decoration. It is also a slice of Smartsheet's own product, which means every design conversation in the interview can be about something you have built.

**What "done" looks like:** A public repo. A live URL on AWS. Two browsers editing one sheet and seeing each other. An automation firing through a queue. A sentence becoming an automation via Claude. An OpenAPI spec. A load test number. An architecture diagram, five ADRs, a HANDOFF.md, and a five-minute Loom. Total: about 60 to 80 hours across three weeks.

**Stack, decided (do not relitigate mid-build):**

| Layer | Choice | Why |
|---|---|---|
| API | Kotlin 2.x, Spring Boot 3.x, Gradle Kotlin DSL, JDK 21 | Smartsheet's stack. Boot is the default for a reason. |
| Data | Postgres 16 via Spring Data JPA, Flyway migrations, plain SQL for bulk cell writes | You know Postgres from Supabase. MySQL knowledge transfers (study guide Part 5). |
| Cache and pub/sub | Redis 7 | Fan-out across API replicas, rate limits, presence |
| Queue | SQS (LocalStack locally) | AWS native, at least once, DLQ built in |
| Worker | Second Gradle module `worker/`, same Kotlin domain code | Separate deployable, shared domain |
| Frontend | React 18, TypeScript strict, Vite, TanStack Query and Virtual, Zustand, zod, Tailwind | Modern, minimal, fast to build |
| Real-time | Spring WebSocket (raw, not STOMP) with JSON messages | Simple to explain, simple to test |
| Auth | Email plus password, bcrypt, short-lived JWT, refresh via re-login | Enough for a portfolio; do not build OAuth |
| AI | Anthropic API, tool use, model pinned | The automation builder |
| Infra | Terraform (VPC, EKS, RDS, ElastiCache, SQS, ECR, S3, CloudFront), Helm chart, GitHub Actions | Posting says AWS and Kubernetes |
| Tests | JUnit 5, MockMvc, Testcontainers, Vitest, React Testing Library, Playwright, k6 | Same verification ethic as the valve trainer |
| Observability | Structured JSON logs with request ids, Micrometer metrics, Actuator health, CloudWatch | "Supporting" an API |

**Scope guardrails (write these on a sticky note):** no offline mode, no CRDT, no formulas beyond same-row arithmetic, no file attachments, no OAuth, no multi-region, no admin UI, no mobile layout. Single tenant model: a user owns sheets and shares them with other users as editor or viewer. If a feature is not in a phase below, it does not exist.

**Stop rule:** if a Smartsheet technical round is scheduled before Phase 5 is done, finish the current phase, run Phase 7 (docs and handoff) on what exists, and stop building. An honest half is worth more than a broken whole.

# PHASE CHECKLIST

| Phase | Hours | Blocking study | Done when |
|---|---|---|---|
| 0 Scaffold | 3 | none | CI green on first push |
| 1 API | 12 | Part 2, Part 4 | 409, idempotency, pagination tests green; Swagger UI up |
| 2 Grid | 10 | Part 3 | Seeded sheet edits at 60 fps; Playwright green |
| 3 Real-time | 10 | Part 7 | Two-replica and race tests green; clip recorded |
| 4 Automations | 14 | Part 5, Part 7 | Exactly-once relay, idempotent worker, loop stop, k6 numbers |
| 5 AI builder | 8 | Part 8 | Eval pass rate in docs, fallback tested |
| 6 AWS | 12 | Part 6 | Live on EKS, zero-downtime restart proven, cost written down |
| 7 Docs | 6 | none | README, HANDOFF, Loom, links verified |
