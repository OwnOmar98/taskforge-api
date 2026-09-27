-- Supporting indexes for the listing endpoints newly converted to
-- Pageable/PageResponse (PR29): org members, project members, projects,
-- labels, task comments. Each of these always filters on its parent id and
-- defaults to sorting by created_at, so a composite index led by the filter
-- column serves both from a single ordered index scan - same reasoning as
-- the task listing indexes (V9). The single-column indexes these tables
-- already had are left in place, matching V9's precedent of adding rather
-- than replacing.
create index idx_memberships_organization_created_at on memberships (organization_id, created_at);
create index idx_project_members_project_created_at on project_members (project_id, created_at);
create index idx_projects_organization_created_at on projects (organization_id, created_at);
create index idx_labels_organization_created_at on labels (organization_id, created_at);
create index idx_task_comments_task_created_at on task_comments (task_id, created_at);
