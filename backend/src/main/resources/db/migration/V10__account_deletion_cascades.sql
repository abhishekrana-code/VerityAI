-- Account deletion is a hard delete, and it must be atomic. With RESTRICT on
-- documents.user_id and conversations.user_id, deleting the user required
-- clearing those two tables first, so an upload committing between the two
-- deletes could make the user delete fail — after the files were already
-- unlinked. CASCADE lets the deletion be one statement: anything that lands
-- mid-delete is removed with the user, and an upload that arrives afterwards
-- fails its own foreign key instead of leaving the account half-deleted.
ALTER TABLE documents
    DROP CONSTRAINT documents_user_id_fkey,
    ADD CONSTRAINT documents_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE conversations
    DROP CONSTRAINT conversations_user_id_fkey,
    ADD CONSTRAINT conversations_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
