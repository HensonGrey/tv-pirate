--liquibase formatted sql

--changeset tvpirate:0011-user-themes
--comment: the account's light/dark choice, so it follows the user to every device; no row means the OS decides
CREATE TABLE user_themes (
    user_id bigint PRIMARY KEY,
    theme varchar(255) NOT NULL CHECK (theme IN ('light', 'dark')),
    CONSTRAINT fk_user_themes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TRIGGER trg_user_themes_touch AFTER INSERT OR UPDATE OR DELETE ON user_themes FOR EACH ROW EXECUTE FUNCTION touch_user_last_activity();
--rollback DROP TABLE user_themes;
