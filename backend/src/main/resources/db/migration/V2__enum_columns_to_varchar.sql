-- Convert PostgreSQL enum columns to VARCHAR to match JPA @Enumerated(EnumType.STRING).
-- Hibernate binds enum names as VARCHAR, which PG rejects against native enum types.

ALTER TABLE documents ALTER COLUMN status TYPE VARCHAR(30) USING status::text;
ALTER TABLE documents ALTER COLUMN status SET DEFAULT 'UPLOADED';

ALTER TABLE messages ALTER COLUMN role TYPE VARCHAR(20) USING role::text;
ALTER TABLE messages ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
ALTER TABLE messages ALTER COLUMN status SET DEFAULT 'COMPLETED';
ALTER TABLE messages ALTER COLUMN evidence_status TYPE VARCHAR(20) USING evidence_status::text;

DROP TYPE document_status;
DROP TYPE message_role;
DROP TYPE message_status;
DROP TYPE evidence_status;
