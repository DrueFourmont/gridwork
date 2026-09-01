-- TEST FIXTURE. NOT A PRODUCT CODE PATH.
--
-- Creates a sheet with 2,000 rows and 5 typed columns for the grid scroll
-- budget in CLAUDE.md ("Grid scroll, 2,000 seeded rows, 60 fps").
--
-- This writes rows and cells directly rather than going through the API. That
-- is a deliberate trade and worth being explicit about: 2,000 calls to
-- POST /rows would be 2,000 HTTP round trips and 2,000 transactions, which
-- takes minutes. This takes about a second.
--
-- The cost of the shortcut is that it proves nothing about the API. It is a
-- fixture for measuring the front end, and it must never be confused with a
-- bulk import feature. If a bulk endpoint is ever wanted, it belongs in the
-- API with its own tests, not here.
--
-- Every version starts at 1, exactly as the API would create them, so the
-- grid's optimistic writes behave identically against seeded data.

\set ON_ERROR_STOP on

do $$
declare
    seed_owner   uuid;
    seed_sheet   uuid;
    column_ids   uuid[];
    column_types text[] := array['TEXT', 'TEXT', 'NUMBER', 'DATE', 'CHECKBOX'];
    column_names text[] := array['Task', 'Owner', 'Estimate', 'Due', 'Done'];
    new_column   uuid;
    i            int;
begin
    -- Reuse the first account rather than inventing one, so the seeded sheet
    -- shows up for whoever is already logged in.
    select id into seed_owner from users order by created_at limit 1;
    if seed_owner is null then
        raise exception 'no users exist yet. register through the app first, then run make seed.';
    end if;

    delete from sheets where name = 'Seeded 2000';

    insert into sheets (owner_id, name) values (seed_owner, 'Seeded 2000')
        returning id into seed_sheet;
    insert into sheet_members (sheet_id, user_id, role) values (seed_sheet, seed_owner, 'OWNER');

    for i in 1..array_length(column_names, 1) loop
        insert into columns (sheet_id, name, type, position)
        values (seed_sheet, column_names[i], column_types[i], i - 1)
        returning id into new_column;
        column_ids := array_append(column_ids, new_column);
    end loop;

    insert into rows (sheet_id, position)
    select seed_sheet, generate_series(0, 1999);

    -- One statement for all 10,000 cells. A loop would be 10,000 round trips
    -- inside the function and would take long enough to be annoying.
    insert into cells (row_id, column_id, sheet_id, value, version, updated_by)
    select
        r.id,
        c.id,
        seed_sheet,
        case c.type
            when 'TEXT'     then case c.position when 0 then 'Task ' || r.position else 'Owner ' || (r.position % 7) end
            when 'NUMBER'   then ((r.position * 7) % 500)::text
            -- rows.position is a bigint, and there is no date + bigint operator.
            when 'DATE'     then (date '2026-01-01' + ((r.position % 365)::int))::text
            when 'CHECKBOX' then case when r.position % 3 = 0 then 'true' else 'false' end
        end,
        1,
        seed_owner
    from rows r
    cross join columns c
    where r.sheet_id = seed_sheet and c.sheet_id = seed_sheet;

    raise notice 'seeded sheet % with 2000 rows and % columns',
        seed_sheet, array_length(column_ids, 1);
end $$;

select
    s.name,
    (select count(*) from rows  where sheet_id = s.id) as rows,
    (select count(*) from columns where sheet_id = s.id) as columns,
    (select count(*) from cells where sheet_id = s.id) as cells
from sheets s
where s.name = 'Seeded 2000';
