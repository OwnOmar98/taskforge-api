create table task_comments (
    id uuid primary key,
    task_id uuid not null references tasks (id),
    author_id uuid not null references users (id),
    body text not null,
    created_at timestamptz not null
);

create index idx_task_comments_task_id on task_comments (task_id);

create table labels (
    id uuid primary key,
    organization_id uuid not null references organizations (id),
    name varchar(100) not null,
    created_at timestamptz not null,
    constraint uq_labels_organization_name unique (organization_id, name)
);

create index idx_labels_organization_id on labels (organization_id);

create table task_labels (
    task_id uuid not null references tasks (id),
    label_id uuid not null references labels (id),
    primary key (task_id, label_id)
);
