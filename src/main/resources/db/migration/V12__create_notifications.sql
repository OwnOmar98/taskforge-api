create table notifications (
    id uuid primary key,
    user_id uuid not null references users (id),
    organization_id uuid not null references organizations (id),
    type varchar(50) not null,
    payload jsonb,
    read_at timestamptz,
    created_at timestamptz not null
);

-- Every listing query filters on user_id unconditionally and defaults to
-- sorting by created_at, same reasoning as idx_tasks_project_created_at.
create index idx_notifications_user_created_at on notifications (user_id, created_at);
