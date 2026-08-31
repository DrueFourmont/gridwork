-- Phase 1 schema: users, sheets and their members, typed columns, rows, and
-- versioned cells, plus the idempotency key store.
--
-- Every mutable resource carries a version, per ADR 0001. Nothing here is
-- updated in place without checking it.

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------- users ----
create table users (
    id              uuid primary key default gen_random_uuid(),
    email           text        not null,
    password_hash   text        not null,
    display_name    text        not null,
    created_at      timestamptz not null default now()
);

-- Case insensitive uniqueness. Without this, Alice@example.com and
-- alice@example.com are two accounts and the owner of neither can explain why.
create unique index users_email_lower_key on users (lower(email));

-- --------------------------------------------------------------- sheets ----
create table sheets (
    id          uuid primary key default gen_random_uuid(),
    owner_id    uuid        not null references users (id),
    name        text        not null check (length(name) between 1 and 200),
    version     bigint      not null default 1 check (version >= 1),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index sheets_owner_id_idx on sheets (owner_id);

-- -------------------------------------------------------- sheet members ----
create table sheet_members (
    sheet_id    uuid        not null references sheets (id) on delete cascade,
    user_id     uuid        not null references users (id),
    role        text        not null check (role in ('OWNER', 'EDITOR', 'VIEWER')),
    created_at  timestamptz not null default now(),
    primary key (sheet_id, user_id)
);

-- The permission check runs on every single request, so it gets its own index
-- rather than relying on the primary key's leading column.
create index sheet_members_user_id_idx on sheet_members (user_id);

-- -------------------------------------------------------------- columns ----
create table columns (
    id          uuid primary key default gen_random_uuid(),
    sheet_id    uuid        not null references sheets (id) on delete cascade,
    name        text        not null check (length(name) between 1 and 100),
    type        text        not null check (type in ('TEXT', 'NUMBER', 'DATE', 'CHECKBOX')),
    position    integer     not null check (position >= 0),
    version     bigint      not null default 1 check (version >= 1),
    created_at  timestamptz not null default now()
);

create unique index columns_sheet_position_key on columns (sheet_id, position);
create unique index columns_sheet_name_key on columns (sheet_id, lower(name));

-- ----------------------------------------------------------------- rows ----
create table rows (
    id          uuid primary key default gen_random_uuid(),
    sheet_id    uuid        not null references sheets (id) on delete cascade,
    position    bigint      not null check (position >= 0),
    version     bigint      not null default 1 check (version >= 1),
    created_at  timestamptz not null default now()
);

-- This is the index the grid reads through: every page of rows is an ordered
-- range scan on (sheet_id, position), which is what makes keyset pagination
-- cheap. See ADR 0005.
create unique index rows_sheet_position_key on rows (sheet_id, position);

-- ---------------------------------------------------------------- cells ----
create table cells (
    row_id      uuid        not null references rows (id) on delete cascade,
    column_id   uuid        not null references columns (id) on delete cascade,
    sheet_id    uuid        not null references sheets (id) on delete cascade,
    value       text,
    version     bigint      not null default 1 check (version >= 1),
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null references users (id),
    primary key (row_id, column_id)
);

-- sheet_id is denormalised onto cells on purpose. Loading a page of the grid
-- otherwise means joining cells to rows just to filter by sheet, on the single
-- hottest read in the product.
create index cells_sheet_id_idx on cells (sheet_id);

-- --------------------------------------------------------- cell history ----
create table cell_history (
    id              bigserial primary key,
    row_id          uuid        not null,
    column_id       uuid        not null,
    sheet_id        uuid        not null,
    old_value       text,
    new_value       text,
    version         bigint      not null,
    changed_at      timestamptz not null default now(),
    changed_by      uuid        not null references users (id)
);

-- Append only. No foreign key to cells, because history has to survive the
-- deletion of the row it describes; that is the entire point of an audit trail.
create index cell_history_cell_idx on cell_history (row_id, column_id, version desc);

-- ----------------------------------------------------- idempotency keys ----
create table idempotency_keys (
    key                 text        not null,
    user_id             uuid        not null references users (id),
    method              text        not null,
    path                text        not null,
    request_fingerprint text        not null,
    response_status     integer     not null,
    response_body       text        not null,
    created_at          timestamptz not null default now(),
    primary key (user_id, method, path, key)
);

-- Scoped to the user so one caller's key cannot collide with another's, and to
-- the route so the same key on a different endpoint is a different operation.
-- See ADR 0003.
create index idempotency_keys_created_at_idx on idempotency_keys (created_at);
