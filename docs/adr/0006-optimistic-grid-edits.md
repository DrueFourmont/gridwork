# ADR 0006: Optimistic cell edits behind a serialised write queue

Date: 2026-09-01
Status: accepted

## Context

A grid has to feel instant. Waiting for a round trip before a typed character
appears is unusable on a slow connection and noticeably laggy on a fast one.

But the API is strict on purpose: every write carries the version it expects,
and a mismatch is a 409 (ADR 0001). So the client cannot simply fire and
forget. It has to keep track of what version each cell is at, and it has to
cope with being told no.

Two specific hazards. First, typing "hello" is five keystrokes; sending five
requests would burn five versions and make everyone else conflict against a
cell nobody has finished editing. Second, editing a cell twice before the first
response returns means the second write has to expect the version the first one
is about to produce, which the client does not know yet.

## Decision

All cell writes go through one queue, in `web/src/grid/writeQueue.ts`, which is
plain TypeScript with no React and no fetch in it. Three rules:

**Coalesce.** Edits accumulate for 250 ms. A later edit to the same cell
replaces the earlier one, so typing is one request carrying the final value.
Edits to different cells in that window go out as one batch, which is what the
`cells:batchUpdate` endpoint is for.

**Serialise.** At most one batch is in flight. Anything typed while a request
is in the air waits for it to settle. This is what removes the second hazard
entirely: the version a write expects is always one the server has confirmed,
never a prediction.

**Remember.** The queue keeps the last confirmed version and value per cell. On
success it learns the new version before the query cache does. On a 409 it
adopts the server's `actualVersion`, so "keep mine" retries at a version that
can actually succeed rather than conflicting forever.

Every failure rolls the cell back to its last confirmed value, not just
conflicts. A grid displaying a value that was never saved is worse than an
error message, because it looks like it worked.

Conflicts surface as a dialog showing both values. There is no automatic merge:
for a single cell there is no sensible way to combine two values, and inventing
one silently discards somebody's work.

## Alternatives considered

- **Write on every keystroke.** Simplest, and wrong: it burns a version per
  character and turns one person typing into a conflict generator.
- **Write only on blur, no queue.** Loses edits when a tab closes, and still
  races itself if a user tabs quickly between cells.
- **Allow concurrent batches with predicted versions.** The client would guess
  that a pending write will succeed and expect version n+1. It usually works,
  and when it does not the failure is a conflict the user cannot explain,
  because it was caused by their own earlier keystroke.
- **Pessimistic: disable the cell until the server answers.** Correct, and it
  reintroduces exactly the latency the optimistic update exists to hide.

## Consequences

Easier: the rules are testable without a browser, and they are. Ten tests in
`writeQueue.test.ts` cover coalescing, serialising, version learning, conflict
adoption, and rollback, with no DOM and no server.

Harder: there is a window, at most 250 ms plus one round trip, in which a
closed tab loses an edit. `beforeunload` flushes as a best effort, which is not
a guarantee. And the optimistic value is shown before it is safe, so any bug in
rollback shows the user a lie. That is why rollback is tested for ordinary
errors as well as conflicts.

## The one-minute spoken version

Typing into a cell updates the screen immediately and queues the write. The
queue waits a quarter second, so typing a word is one request rather than one
per letter, and it only ever has one request in flight, so an edit can never
race an earlier edit of its own. It remembers the version the server confirmed
for each cell, and when the server rejects a write it adopts the version the
server reported, which is what lets "keep mine" actually succeed on the retry.
Anything that fails rolls back, because showing a value that was not saved is
worse than showing an error.
