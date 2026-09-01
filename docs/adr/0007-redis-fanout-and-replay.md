# ADR 0007: Redis pub/sub for live updates, cell_history for replay

Date: 2026-09-01
Status: accepted

## Context

Two people editing one sheet should see each other's changes without pressing
refresh. The API runs as more than one replica, so the person who made a change
and the person who should see it are usually connected to different processes.
Something has to carry the change between them.

There is also a harder question hiding behind that one. Whatever carries it
will sometimes fail to, because sockets drop and processes restart. What is
owed to a client that was not listening?

## Decision

**Redis pub/sub carries live updates, and it is best effort.** A committed cell
write is published to a channel per sheet. Every replica with a viewer of that
sheet is subscribed and forwards the change down its websockets. Redis pub/sub
delivers to whoever is connected at that instant and forgets everyone else,
with no persistence and no acknowledgement.

**Published after commit, never during.** The write raises a Spring
application event, and a `@TransactionalEventListener(AFTER_COMMIT)` publishes
it. Publishing inside the transaction would let another replica hear about a
change and then read the old value, because the write is not visible yet, and a
transaction that later rolled back would have announced something that never
happened.

**cell_history is the replay log.** It was built in Phase 1 as an audit trail:
append only, one row per cell write, with a bigserial id. That id is a
monotonic sequence, so "everything in this sheet after N" is a range scan. Live
updates carry the same sequence a replay would give them, so a client can tell
whether it has already seen a change. No second log was built, because two logs
means two things to keep in step.

**A client that has fallen too far behind is refused, not served.** The rule is
in `domain/Replay.kt` and is pure arithmetic: no cursor, a cursor from the
future, or more than 500 missed changes all produce a resync instead of a
replay, and the client refetches. Refusing loudly is better than a replay that
takes longer than the refetch would have.

**Writes do not go over the socket.** It is one way. A write goes over REST so
it gets the same validation, versioning, and idempotency as every other write,
and there is one write path rather than two that have to agree.

## Alternatives considered

- **Sticky sessions, so a sheet's viewers share a replica.** Removes the need
  for Redis and reintroduces state in the load balancer, which is the thing
  CLAUDE.md says replicas must not have. It also fails the moment a replica
  restarts.
- **Postgres LISTEN/NOTIFY.** One less moving part, and it holds a database
  connection per listening replica, has an 8000 byte payload limit, and puts
  fan-out load on the database that is already the bottleneck.
- **SQS for live updates.** Wrong shape. SQS is a queue: one consumer takes
  each message. Fan-out needs every replica to get every message, and SQS
  latency is far above what typing should feel like. SQS is right for
  automations in Phase 4, where at-least-once delivery matters more than speed.
- **STOMP over the websocket.** Brings a broker abstraction, frames, and a
  client library for what is one channel and five message types.
- **Guaranteed delivery over the socket.** Per client queues that survive a pod
  restart, which is most of a message broker. The outbox in Phase 4 exists for
  the events that genuinely cannot be missed.

## Consequences

Easier: replicas stay stateless, a replica does no work for a sheet nobody on
it is watching, and the audit trail earned a second job for free.

Harder, and worth stating plainly:

**Live updates make conflicts rarer, which changed the tests.** A connected
browser now usually receives the other person's change before the user types,
so they edit the current version and no 409 happens at all. The conflict tests
had to start suppressing the websocket to reproduce a conflict. That is the
feature working, but it means the 409 path is now the degraded path rather than
the common one, and it must keep being tested deliberately.

**Echo suppression must be by version, not by user.** The obvious filter, ignore
changes whose author is me, is wrong: two tabs signed in as the same person
then ignore each other. Applying a change only when it carries a version newer
than the cache handles both, and the two browser test is what caught it.

**A message dropped between commit and delivery is invisible until reconnect.**
That is the accepted cost of best effort, and it is only acceptable because
replay exists. If replay ever breaks, this design becomes silently lossy.

## The one-minute spoken version

When a cell is written, the change is published to Redis after the transaction
commits, and every API replica with someone watching that sheet pushes it down
their websocket. Pub/sub is best effort, so a client that was disconnected
misses things. That is fine because every write is already in cell_history with
a monotonic id, so a reconnecting client says which sequence it last saw and
gets the gap replayed. If the gap is too big it is told to refetch instead. The
socket only carries changes down; writes still go over REST so they get the same
versioning and idempotency as everything else.
