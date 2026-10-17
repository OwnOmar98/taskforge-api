-- Domain events, inserted in the same transaction as the change that raised
-- them and published to the message broker afterwards by OutboxEventRelay.
create table outbox_events (
    id uuid primary key,
    event_type varchar(100) not null,
    payload jsonb not null,
    occurred_at timestamptz not null,
    status varchar(20) not null,
    attempts int not null default 0,
    next_attempt_at timestamptz not null,
    claimed_until timestamptz,
    last_error text,
    published_at timestamptz
);

create index idx_outbox_events_due on outbox_events (next_attempt_at) where status = 'PENDING';
create index idx_outbox_events_lease on outbox_events (claimed_until) where status = 'IN_FLIGHT';

-- Each consumer records the events it has handled, in the same transaction as
-- its work, so a message delivered twice is only acted on once.
create table processed_events (
    consumer varchar(100) not null,
    event_id uuid not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, event_id)
);
