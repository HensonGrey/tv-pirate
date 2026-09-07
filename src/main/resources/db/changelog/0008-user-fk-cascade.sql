--liquibase formatted sql

--changeset tvpirate:0008-user-fk-cascade
--comment: ON DELETE CASCADE on every FK to users(id), so a new user-scoped table never needs hand-adding to the guest cleanup sweep
ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_user_id_fkey;
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE watch_progress DROP CONSTRAINT watch_progress_user_id_fkey;
ALTER TABLE watch_progress ADD CONSTRAINT fk_watch_progress_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE favourites DROP CONSTRAINT favourites_user_id_fkey;
ALTER TABLE favourites ADD CONSTRAINT fk_favourites_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
--rollback ALTER TABLE refresh_tokens DROP CONSTRAINT fk_refresh_tokens_user;
--rollback ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
--rollback ALTER TABLE watch_progress DROP CONSTRAINT fk_watch_progress_user;
--rollback ALTER TABLE watch_progress ADD CONSTRAINT watch_progress_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
--rollback ALTER TABLE favourites DROP CONSTRAINT fk_favourites_user;
--rollback ALTER TABLE favourites ADD CONSTRAINT favourites_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
