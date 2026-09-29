alter table tasks
    add column deleted_at timestamptz,
    add column deleted_by uuid references users (id);

alter table projects
    add column deleted_at timestamptz,
    add column deleted_by uuid references users (id);

-- A plain unique constraint would let a soft-deleted project hold on to its
-- key forever. Only live projects compete for a key; restoring a deleted one
-- whose key has since been reused is rejected in ProjectService instead.
alter table projects drop constraint uq_projects_organization_key;
create unique index uq_projects_organization_key_active on projects (organization_id, key)
    where deleted_at is null;
