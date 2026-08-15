--liquibase formatted sql

--changeset tvpirate:0002-add-profile-picture-url
--comment: provider-supplied avatar URL (Google "picture" claim); null for guests
ALTER TABLE users ADD COLUMN profile_picture_url varchar(512);
--rollback ALTER TABLE users DROP COLUMN profile_picture_url;
