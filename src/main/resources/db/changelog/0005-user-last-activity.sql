--liquibase formatted sql

--changeset tvpirate:0005-user-last-activity
--comment: per-user activity clock, kept fresh by triggers (see 0006-touch-triggers.xml); feeds the daily guest sweep
ALTER TABLE users ADD COLUMN last_activity_at timestamp(6) with time zone;
UPDATE users SET last_activity_at = created_at WHERE last_activity_at IS NULL;
ALTER TABLE users ALTER COLUMN last_activity_at SET NOT NULL;
--rollback ALTER TABLE users DROP COLUMN last_activity_at;
