# ADR 0001: Cell-level rows with optimistic versioning

Date: 2026-08-31
Status: accepted

## Context

Multiple users edit one sheet at the same time. Two writers can target the
same cell within milliseconds. We need to detect lost updates without holding
database locks across HTTP requests, and we need an audit trail per cell.

## Decision

Cells are their own rows in Postgres, keyed by (row_id, column_id), each with
a `version` bigint. A write carries the version it expects. The update is
`UPDATE cells SET value = ?, version = version + 1 WHERE row_id = ? AND
column_id = ? AND version = ?`. Zero rows affected means conflict. A batch is
one transaction; any conflict aborts the whole batch and returns 409 with the
current value and version of every conflicting cell.

## Alternatives considered

- One JSON document per row: simpler writes, but two users editing different
  cells in the same row would conflict, and cell history and indexing become
  application logic.
- Pessimistic locks (SELECT FOR UPDATE): holds locks across a network round
  trip; does not survive a client that disappears.
- CRDT or OT: correct for free-text documents, heavy for a grid where
  per-cell last-writer-wins with conflict detection is what shipping products
  actually do.

## Consequences

Easier: conflict detection is exact, cell history is a plain append-only
table, sharding by sheet_id stays clean. Harder: a 100-column row is 100 rows
in the table, so bulk reads need a good index and bulk writes use plain SQL
rather than JPA. Revisit if a sheet exceeds a few million cells.

## The one-minute spoken version

Each cell has a version number. When you save, you say which version you saw.
If someone got there first, the save is rejected and you get their value back
and the UI shows the merge. No locks, no lost edits, and the same trick works
for rows and for automations. The cost is more rows in the database, which an
index on (sheet_id, position) and batched SQL handle.
