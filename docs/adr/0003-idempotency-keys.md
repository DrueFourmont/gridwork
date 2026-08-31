# ADR 0003: Idempotency keys claimed with insert on conflict

Date: 2026-08-31
Status: accepted

## Context

A client POSTs to create a sheet. The connection drops before the response
arrives. The client cannot tell whether the sheet was created, so it has two bad
options: retry and risk two sheets, or do not retry and risk none.

This is not hypothetical on a mobile network, and it is the same problem a
double clicked button creates locally.

## Decision

Unsafe requests accept an `Idempotency-Key` header. The key is scoped to
`(user_id, method, path, key)`, so one caller's key cannot collide with
another's and the same key on a different route is a different operation.

The claim is a single statement:

```sql
insert into idempotency_keys (...) values (..., 0, '')
on conflict (user_id, method, path, key) do nothing
```

The caller that affects one row owns the operation. Anyone else affects zero and
either replays the stored response or, if the first is still running, gets a 409
telling them to retry shortly. Postgres decides the winner, not application
timing, so two simultaneous requests cannot both proceed.

The claim and the completion run in their own transactions
(`REQUIRES_NEW`). If the claim shared the operation's transaction it would be
invisible to a concurrent request until commit, which is precisely when it is
needed.

A replay whose body hashes differently from the original is a 422, not a replay.
The same key meaning two different operations is a client bug, and returning the
first result would hide it.

A failed operation deletes its reservation. Without that, a transient database
error would poison the key forever and every retry would see a reservation that
never completes.

## Alternatives considered

- Select then insert: a race between the select and the insert, which is the
  exact window the feature exists to close.
- Do the work, then insert and let the unique constraint decide: both callers
  do the work, which for "create a sheet" means two sheets.
- Cache the response in Redis: loses the guarantee on a Redis restart, and the
  guarantee is the whole point. Redis is best effort in this system by design.
- Make the endpoints naturally idempotent with client supplied ids: a real
  option, and better where it fits, but it does not cover
  `cells:batchUpdate`, where the operation is not a creation.

## Consequences

Easier: clients can retry any unsafe request safely, which is the precondition
for sane behaviour on a flaky network. Phase 4's worker will need exactly this
mechanism for at-least-once SQS delivery, and it will reuse it.

Harder: a stored response is frozen. Changing a DTO does not change what an
already stored replay returns, which is correct but surprising. The table also
grows without bound; it needs a scheduled delete of old keys, which is not built
yet and is recorded in HANDOFF as a known issue.

## The one-minute spoken version

You send a key with a create request. I insert that key with "on conflict do
nothing", so if two requests arrive at once the database picks exactly one
winner. The winner does the work and stores its response against the key. The
loser gets that same response back rather than doing the work again. If you
reuse a key with a different body I reject it, because that means you have a
bug, and quietly returning the old answer would hide it.
