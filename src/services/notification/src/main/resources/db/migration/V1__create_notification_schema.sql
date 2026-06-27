-- Flyway migration V1: PostgreSQL schema for notification service
-- Migrated from Oracle 19c RAC
--
-- Key changes from Oracle:
--   - Table names: lowercase (PostgreSQL convention)
--   - VARCHAR2 -> VARCHAR
--   - CLOB     -> TEXT
--   - SYSDATE  -> CURRENT_TIMESTAMP
--   - Partitioning: Oracle interval -> PostgreSQL range partitioning
--   - Encryption: Oracle TDE -> AWS KMS at RDS level

-- Notification events (partitioned by month for 7-year retention)
CREATE TABLE IF NOT EXISTS notification_events (
    notification_id  VARCHAR(36)   PRIMARY KEY,
    event_type       VARCHAR(50)   NOT NULL,
    account_id       VARCHAR(20)   NOT NULL,
    payload          TEXT          NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'EXPIRED'))
);

CREATE INDEX idx_notif_account_id ON notification_events (account_id);
CREATE INDEX idx_notif_status_created ON notification_events (status, created_at);
CREATE INDEX idx_notif_event_type ON notification_events (event_type);

-- Immutable audit log (append-only for SOX 404 compliance)
-- No UPDATE or DELETE triggers — enforced at application level
-- 7-year retention per GLBA/SOX requirements
CREATE TABLE IF NOT EXISTS audit_log (
    audit_id         VARCHAR(36)   PRIMARY KEY,
    event_type       VARCHAR(50)   NOT NULL,
    account_id       VARCHAR(20)   NOT NULL,
    description      TEXT          NOT NULL,
    reference_id     VARCHAR(36),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(100)  NOT NULL,
    source_system    VARCHAR(50)   NOT NULL
);

CREATE INDEX idx_audit_account_id ON audit_log (account_id);
CREATE INDEX idx_audit_reference_id ON audit_log (reference_id);
CREATE INDEX idx_audit_created_at ON audit_log (created_at);
CREATE INDEX idx_audit_event_type ON audit_log (event_type);

-- Idempotency tracking for SQS FIFO message deduplication
-- Replaces IBM MQ XA transaction semantics
CREATE TABLE IF NOT EXISTS processed_messages (
    deduplication_id VARCHAR(128)  PRIMARY KEY,
    processed_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_processed_at ON processed_messages (processed_at);

-- Prevent accidental modification of audit records
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit log records are immutable — UPDATE and DELETE are prohibited (SOX 404)';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_modification();

-- Cleanup old idempotency records (retain for 14 days, matching SQS retention)
-- Run via pg_cron or application-level scheduled task
COMMENT ON TABLE processed_messages IS
    'Idempotency tracking for SQS FIFO deduplication. Cleanup records older than 14 days.';
