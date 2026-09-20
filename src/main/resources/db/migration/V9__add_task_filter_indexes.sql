-- Supporting indexes for the filters/sort added in the dynamic task listing.
-- Every listing query filters on project_id unconditionally (belongsToProject
-- is always applied) and defaults to sorting by created_at, so composite
-- indexes led by project_id let Postgres satisfy the filter and the sort
-- from a single ordered index scan instead of filtering then sorting
-- separately. task_labels has no project_id column, so its index stays
-- standalone; the composite primary key (task_id, label_id) only serves
-- lookups that start from task_id.
create index idx_tasks_project_created_at on tasks (project_id, created_at);
create index idx_tasks_project_status on tasks (project_id, status);
create index idx_tasks_project_priority on tasks (project_id, priority);
create index idx_task_labels_label_id on task_labels (label_id);
