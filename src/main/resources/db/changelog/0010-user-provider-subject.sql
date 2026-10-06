--liquibase formatted sql

--changeset tvpirate:0010-user-provider-subject
--comment: the provider's stable account id (Google "sub") - an email can change hands, this never does; null for guests
ALTER TABLE users ADD COLUMN provider_subject varchar(255);
CREATE UNIQUE INDEX uq_users_provider_subject ON users (provider, provider_subject);
--rollback DROP INDEX uq_users_provider_subject;
--rollback ALTER TABLE users DROP COLUMN provider_subject;
