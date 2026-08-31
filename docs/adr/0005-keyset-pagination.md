# ADR 0005: Keyset cursors, not offsets

Date: 2026-08-31
Status: accepted

## Context

The grid has to page through rows, and a sheet can hold tens of thousands of
them. Two things matter: the tenth page must not be slower than the first, and a
row must not be skipped or repeated because someone else inserted one while the
caller was paging.

## Decision

Pagination is keyset. A cursor names a position in the sort order, not a count
of skipped records.

Rows are ordered by `(sheet_id, position)` and the cursor carries the last
position seen, so the next page is `where position > :cursor order by position
limit :n`. That is an index range scan on `rows_sheet_position_key`, and it
costs the same on page 100 as on page 1.

Sheets are ordered by `(created_at desc, id desc)` and the cursor carries both,
because `created_at` alone is not unique and two sheets created in the same
millisecond would make the boundary ambiguous.

Cursors are base64 and documented as opaque. Base64 is not security, it is a
fence: it makes it obvious that the contents are not part of the contract, so a
client cannot build one by hand and then break when the sort order changes.

Each query asks for `limit + 1` rows. If it gets them, there is another page and
the extra row is dropped. That avoids a second `count(*)`, which on a large
sheet costs more than the page itself.

## Alternatives considered

- `offset` and `limit`: the obvious choice and wrong on both counts. Postgres
  must walk and discard every skipped row, so page 100 reads 100 pages worth of
  rows. Worse, if a row is inserted before the cursor between two requests, one
  row shifts past the offset and is never returned at all. There is a test for
  exactly this.
- A total count with every page: a full scan per request for a number the UI
  rarely needs. A virtualised grid needs "is there more", not "how many".
- Snapshot the result set server side: correct, stateful, and against the rule
  that API replicas hold nothing that must survive a restart.

## Consequences

Easier: constant time paging, and correctness while the sheet is being edited,
which is the normal case in a collaborative product.

Harder: no jumping to page 50, because there is no page 50 without counting. A
virtualised grid scrolls rather than paginating, so this costs nothing here, but
it would matter for a UI with numbered pages. Sorting by an arbitrary column
later means the cursor has to carry that column plus a tiebreaker.

## The one-minute spoken version

Paging by offset means the database walks and throws away everything you skipped,
so the further you scroll the slower it gets, and if someone inserts a row while
you are scrolling you silently miss one. Instead each page returns a cursor
saying where it stopped, and the next query is "give me rows after this
position", which is an index seek and costs the same on page one or page one
hundred. The cursor is opaque so clients cannot build one and depend on its
shape.
