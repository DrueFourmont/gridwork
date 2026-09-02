# ADR 0009: A transactional outbox, an at-least-once relay, and an idempotent worker

Date: 2026-09-02
Status: accepted

## Context

When a cell changes, an automation may need to run. The obvious implementation
is to publish to the queue from inside the request that made the change. It is
also wrong in two directions at once:

- Publish before the transaction commits, and a rollback leaves an event
  describing something that never happened.
- Publish after it commits, and a crash or a network blip between the two
  leaves a committed change with no event, and an automation that silently
  never runs.

There is no ordering that fixes this, because Postgres and SQS cannot commit
together. That is the problem the outbox pattern exists for.

## Decision

**Events are written to `outbox_events` in the same transaction as the data.**
The cell rows, the history rows, and the event row commit together or not at
all. No network call happens in the request path, so the write cannot fail
because a queue was briefly unreachable.

**A relay publishes them afterwards.** It runs on a one second schedule in
every API replica and claims rows with:

```sql
select ... from outbox_events where published_at is null
order by id limit :n for update skip locked
```

`for update skip locked` is what allows every replica to run a relay with no
leader election and no scheduler lock. Each pass takes rows nobody else holds
and the others step over them rather than blocking.

**The relay is at-least-once, deliberately.** It can publish to SQS and then
fail before recording that it did, and the next pass republishes. Making the
relay exactly-once would require a distributed transaction, which is the thing
the outbox exists to avoid.

**So the worker is idempotent.** Every event id is inserted into
`processed_events` with `on conflict do nothing`, inside the same transaction
as the actions it takes. A redelivery finds the row and does nothing. Two
workers racing the same message means one insert wins. "Exactly once" in this
system is a claim about effects, not about deliveries, and this is where it is
made true.

**Loops are stopped in two places.** An automation whose action writes its own
trigger column is refused when saved. Two automations pointing at each other
cannot be caught that way, since each is individually reasonable, so every
event carries a depth: a human edit is zero, an automation's write is one more
than the change that caused it, and at three the engine stops. An action that
would write the value already present is dropped, so a pointless loop does not
even burn its three rounds.

**Poison messages go to a dead letter queue.** The queue's redrive policy moves
a message aside after five receives. Retry logic in the consumer would
duplicate what SQS already does correctly.

## Alternatives considered

- **Publish from the request.** Covered above; there is no correct ordering.
- **Postgres LISTEN/NOTIFY instead of a queue.** No durability, no redelivery,
  no dead letter queue. It is the right tool for the best effort live updates
  in ADR 0007 and the wrong one here, where a missed event means an automation
  that should have run and did not.
- **A single dedicated relay process.** Simpler to reason about and a single
  point of failure that stops every automation when it restarts. SKIP LOCKED
  costs one clause and removes the question.
- **Exactly-once delivery.** Not available from SQS, and not available in
  general without consensus. Idempotent consumers are the answer everyone
  actually ships.

## Consequences

Easier: a cell write can never produce a missing or a phantom event. Any
replica can relay. A duplicate delivery is a no op rather than a duplicated
action, which is proven by a test that republishes every event and asserts the
target cell's version did not move.

Harder, and measured rather than guessed:

**The worker is the bottleneck by a wide margin.** Under 50 virtual users the
API sustained 2,818 cell writes per second. The relay drained about 4,000
events per second. A single worker processed **78 events per second**. That is
roughly a thirty six times gap, and on a sheet with no automations at all,
where every event is a skip. Nothing here is broken; the number simply says the
worker is where capacity has to come from, by running more of them or by
processing a batch concurrently instead of serially.

**`outbox_events` and `processed_events` grow without bound.** Neither is
pruned. After one thirty second load test the outbox held 88,583 rows. This
needs a scheduled delete of published events older than some window, and it is
not built.

**Holding row locks across an SQS call is a real cost.** The relay publishes
inside the claiming transaction, so a slow SQS call holds locks. It buys the
guarantee that two relays cannot publish the same event concurrently, and the
trade should be revisited if SQS latency ever becomes the constraint.

## The one-minute spoken version

When a cell changes, the event goes into an outbox table in the same
transaction as the change, so there is never a change without an event or an
event without a change. A relay in every API replica polls that table with
`for update skip locked`, so they share the work without a leader, and
publishes to SQS. That publish is at-least-once, so the worker deduplicates on
the event id with an insert that conflicts, in the same transaction as the work
it does. Automation loops are stopped by refusing the self referential case at
save time and by carrying a depth on every event that stops at three. The
worker does everything through the same services the API uses, so an
automation's write is versioned, attributed, and audited exactly like a
person's.
