--liquibase formatted sql

--changeset tvpirate:0007-hot-query-indexes
--comment: two indexes behind queries that already run on every page load / daily sweep with no usable index today
CREATE INDEX idx_watch_progress_user_updated ON watch_progress (user_id, updated_at DESC);
CREATE INDEX idx_users_provider_activity ON users (provider, last_activity_at);
--rollback DROP INDEX idx_watch_progress_user_updated;
--rollback DROP INDEX idx_users_provider_activity;
