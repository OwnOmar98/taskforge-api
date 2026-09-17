create table organizations (
    id uuid primary key,
    name varchar(255) not null,
    slug varchar(255) not null,
    created_at timestamptz not null,
    constraint uq_organizations_slug unique (slug)
);

create table users (
    id uuid primary key,
    email varchar(255) not null,
    password_hash varchar(255) not null,
    full_name varchar(255) not null,
    created_at timestamptz not null,
    constraint uq_users_email unique (email)
);

create table memberships (
    id uuid primary key,
    organization_id uuid not null references organizations (id),
    user_id uuid not null references users (id),
    role varchar(50) not null,
    created_at timestamptz not null,
    constraint uq_memberships_organization_user unique (organization_id, user_id)
);

create index idx_memberships_organization_id on memberships (organization_id);
create index idx_memberships_user_id on memberships (user_id);
