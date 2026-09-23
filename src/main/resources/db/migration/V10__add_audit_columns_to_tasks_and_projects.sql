-- Backfilled with the migration's own execution time as a placeholder, since
-- that's the closest available approximation for rows that predate this
-- column - there is no way to recover their real last-modified time.
alter table tasks add column updated_at timestamptz not null default now();
alter table tasks add column created_by uuid references users (id);

alter table projects add column updated_at timestamptz not null default now();
alter table projects add column created_by uuid references users (id);
