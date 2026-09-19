create table tasks (
    id uuid primary key,
    project_id uuid not null references projects (id),
    title varchar(255) not null,
    description text,
    status varchar(50) not null,
    priority varchar(50) not null,
    due_date date,
    assignee_id uuid references users (id),
    version bigint not null default 0,
    created_at timestamptz not null
);

create index idx_tasks_project_id on tasks (project_id);
create index idx_tasks_assignee_id on tasks (assignee_id);
