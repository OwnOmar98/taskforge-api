create table audit_logs (
    id uuid primary key,
    organization_id uuid not null references organizations (id),
    actor_id uuid references users (id),
    action varchar(100) not null,
    entity_type varchar(100) not null,
    entity_id uuid not null,
    metadata jsonb,
    created_at timestamptz not null
);

create index idx_audit_logs_organization_id on audit_logs (organization_id, created_at);
