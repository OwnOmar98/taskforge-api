create table media (
    id uuid primary key,
    storage_key varchar(500) not null,
    filename varchar(255) not null,
    content_type varchar(255) not null,
    size_bytes bigint not null,
    visibility varchar(20) not null,
    uploaded_by uuid not null references users (id),
    created_at timestamptz not null,
    constraint uq_media_storage_key unique (storage_key)
);

-- Pure join table, same shape as task_labels: media stays a standalone,
-- reusable concept with no knowledge of who links to it.
create table task_attachments (
    task_id uuid not null references tasks (id),
    media_id uuid not null references media (id),
    primary key (task_id, media_id)
);

create index idx_task_attachments_task_id on task_attachments (task_id);
