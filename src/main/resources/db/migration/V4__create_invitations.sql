create table invitations (
    id uuid primary key,
    organization_id uuid not null references organizations (id),
    email varchar(255) not null,
    role varchar(50) not null,
    token_hash varchar(255) not null,
    invited_by uuid not null references users (id),
    expires_at timestamptz not null,
    accepted_at timestamptz,
    declined_at timestamptz,
    created_at timestamptz not null,
    constraint uq_invitations_token_hash unique (token_hash)
);

create index idx_invitations_organization_id on invitations (organization_id);
create index idx_invitations_email on invitations (email);
