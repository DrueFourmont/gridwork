-- Phase 3 needs to answer one new question: "what changed in this sheet after
-- sequence N". cell_history already records every cell write with a bigserial
-- id, so it is already the replay log; it just was not indexed for reading in
-- that direction.
--
-- The existing index is on (row_id, column_id, version desc), which serves
-- "the history of this one cell". Replay is a range scan over one sheet in id
-- order, which that index cannot help with at all.

create index cell_history_sheet_seq_idx on cell_history (sheet_id, id);
