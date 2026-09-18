create table refresh_tokens (
    id uuid primary key,
    user_id uuid not null references users (id),
    token_hash varchar(255) not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    replaced_by uuid references refresh_tokens (id),
    created_at timestamptz not null,
    constraint uq_refresh_tokens_token_hash unique (token_hash),
    -- A token can be the replacement for at most one other token. Postgres
    -- allows multiple NULLs under a unique constraint, so unrotated tokens
    -- (replaced_by still null) are unaffected.
    constraint uq_refresh_tokens_replaced_by unique (replaced_by)
);

create index idx_refresh_tokens_user_id on refresh_tokens (user_id);
