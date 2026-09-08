--liquibase formatted sql

--changeset tvpirate:0009-refresh-tokens-user-index
--comment: index the referencing side of the users FK. Postgres indexes the referenced side only, so every ON DELETE CASCADE and every deleteAllByUser was a sequential scan over refresh_tokens - once per guest the nightly sweep removes. watch_progress and favourites are already covered incidentally by indexes that lead with user_id.
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
--rollback DROP INDEX idx_refresh_tokens_user;
