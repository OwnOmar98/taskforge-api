create table webhooks (
    id uuid primary key,
    organization_id uuid not null references organizations (id),
    url varchar(2048) not null,
    secret varchar(255) not null,
    created_at timestamptz not null
);

create index idx_webhooks_organization_id on webhooks (organization_id);
