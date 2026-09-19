create table projects (
    id uuid primary key,
    organization_id uuid not null references organizations (id),
    key varchar(50) not null,
    name varchar(255) not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    constraint uq_projects_organization_key unique (organization_id, key)
);

create index idx_projects_organization_id on projects (organization_id);

create table project_members (
    id uuid primary key,
    project_id uuid not null references projects (id),
    user_id uuid not null references users (id),
    role varchar(50) not null,
    created_at timestamptz not null,
    constraint uq_project_members_project_user unique (project_id, user_id)
);

create index idx_project_members_project_id on project_members (project_id);
create index idx_project_members_user_id on project_members (user_id);
