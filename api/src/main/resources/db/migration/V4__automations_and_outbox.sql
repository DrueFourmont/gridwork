-- Phase 4: automations, the transactional outbox, and the worker's dedupe log.

-- ----------------------------------------------------------- automations ----
create table automations (
    id                  uuid primary key default gen_random_uuid(),
    sheet_id            uuid        not null references sheets (id) on delete cascade,
    name                text        not null check (length(name) between 1 and 200),
    enabled             boolean     not null default true,
    trigger_type        text        not null check (trigger_type in ('COLUMN_CHANGED', 'COLUMN_CHANGED_TO')),
    trigger_column_id   uuid        not null references columns (id) on delete cascade,
    trigger_value       text,
    action_column_id    uuid        not null references columns (id) on delete cascade,
    action_value        text,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null references users (id)
);

-- The worker loads automations by sheet on every event, so this is the hot
-- path for the whole automation engine.
create index automations_sheet_idx on automations (sheet_id) where enabled;

create table automation_conditions (
    id              bigserial primary key,
    automation_id   uuid    not null references automations (id) on delete cascade,
    column_id       uuid    not null references columns (id) on delete cascade,
    comparator      text    not null check (comparator in
                        ('EQUALS', 'NOT_EQUALS', 'CONTAINS', 'GREATER_THAN',
                         'LESS_THAN', 'IS_EMPTY', 'IS_NOT_EMPTY')),
    value           text
);

create index automation_conditions_automation_idx on automation_conditions (automation_id);

-- --------------------------------------------------------------- outbox ----
--
-- The whole point of this table is that a row goes into it in the SAME
-- transaction as the cell write it describes. Either both happen or neither
-- does. Publishing to SQS from inside the request instead would leave two ways
-- to be wrong: an event published for a write that then rolled back, or a write
-- committed with no event because the network blipped.
--
-- A relay reads this table and publishes to SQS afterwards. That turns an
-- impossible problem, two systems committing atomically, into an ordinary one,
-- a queue of work to retry until it succeeds.
create table outbox_events (
    id              bigserial primary key,
    aggregate_type  text        not null,
    aggregate_id    uuid        not null,
    event_type      text        not null,
    payload         jsonb       not null,
    created_at      timestamptz not null default now(),
    published_at    timestamptz,
    attempts        integer     not null default 0,
    last_error      text
);

-- The relay's only query: unpublished rows, oldest first. A partial index
-- keeps it proportional to the backlog rather than to the table, which matters
-- because published rows accumulate until something prunes them.
create index outbox_unpublished_idx on outbox_events (id) where published_at is null;

-- ---------------------------------------------------- processed messages ----
--
-- SQS delivers at least once. That is not a defect to work around, it is the
-- contract, and the standard answer is to make the consumer idempotent rather
-- than to wish for exactly-once delivery.
--
-- The worker records every outbox event id it has finished. A redelivery finds
-- the row already there and does nothing. The primary key is the dedupe: two
-- workers racing the same message means one insert wins and one conflicts.
create table processed_events (
    event_id        bigint      primary key,
    processed_at    timestamptz not null default now(),
    actions_taken   integer     not null default 0
);
