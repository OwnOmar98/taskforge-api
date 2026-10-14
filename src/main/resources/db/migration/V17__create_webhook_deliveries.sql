-- The transactional outbox for webhook delivery: a row is inserted in the
-- same transaction as the change that triggers it, so a committed change can
-- never lose its webhook (the old in-memory @Async hand-off could, on a crash
-- between commit and send) and a rolled-back one never produces one.
create table webhook_deliveries (
    id uuid primary key,
    -- Deleting a webhook drops its undelivered (and delivered) history with it.
    webhook_id uuid not null references webhooks (id) on delete cascade,
    organization_id uuid not null references organizations (id),
    event_type varchar(100) not null,
    payload jsonb not null,
    -- One per logical delivery, reused on every retry so receivers can dedupe.
    idempotency_key uuid not null unique,
    status varchar(20) not null,
    attempts int not null default 0,
    next_attempt_at timestamptz not null,
    -- Lease on an IN_FLIGHT row: past this, the claiming instance is presumed
    -- dead and another relay may reclaim the row.
    claimed_until timestamptz,
    last_error text,
    created_at timestamptz not null,
    delivered_at timestamptz
);

-- The relay's two claim conditions, each served by a small partial index
-- rather than one over every delivered row the table accumulates.
create index idx_webhook_deliveries_due on webhook_deliveries (next_attempt_at) where status = 'PENDING';
create index idx_webhook_deliveries_lease on webhook_deliveries (claimed_until) where status = 'IN_FLIGHT';
create index idx_webhook_deliveries_webhook_created on webhook_deliveries (webhook_id, created_at);
