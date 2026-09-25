-- Nullable, and only ever set by the digest job: every other notification
-- type leaves this null, and Postgres allows any number of rows with a null
-- in a unique constraint's column - only rows where the job actually sets a
-- real date get the idempotency check.
alter table notifications add column digest_date date;

-- Enforced here, not just in application code: a check-then-insert in Java
-- can't survive two overlapping runs (e.g. after a crash/retry) racing each
-- other, but a database constraint always wins - one of the two INSERTs is
-- guaranteed to violate it, and the job uses ON CONFLICT DO NOTHING to treat
-- that as "already sent today" rather than an error.
alter table notifications add constraint uq_notifications_digest
    unique (user_id, organization_id, digest_date);
