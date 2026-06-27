-- Migration: Oracle AUDIT_LOG -> PostgreSQL audit_log
-- APPEND-ONLY table: No UPDATE or DELETE operations permitted.
-- SOX Section 404 requires complete, immutable audit trails.
-- Retention: 7 years minimum per GLBA/SOX requirements.
--
-- Oracle specifics replaced:
--   VARCHAR2      -> VARCHAR
--   SYSDATE       -> CURRENT_TIMESTAMP
--   Oracle audit  -> PostgreSQL trigger-based immutability

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id        VARCHAR(36)   NOT NULL,
    event_type      VARCHAR(50)   NOT NULL,
    account_id      VARCHAR(20)   NOT NULL,
    description     TEXT          NOT NULL,
    reference_id    VARCHAR(36),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(100)  NOT NULL,
    source_system   VARCHAR(50)   NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (audit_id, created_at)
) PARTITION BY RANGE (created_at);

-- Default partition for current data
CREATE TABLE IF NOT EXISTS audit_log_default
    PARTITION OF audit_log DEFAULT;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_audit_account_id
    ON audit_log (account_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_audit_reference_id
    ON audit_log (reference_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_audit_event_type
    ON audit_log (event_type, created_at ASC);

-- Immutability enforcement: prevent UPDATE and DELETE on audit_log
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit log records are immutable. UPDATE and DELETE operations are prohibited (SOX 404 compliance).';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_immutable_update ON audit_log;
CREATE TRIGGER trg_audit_immutable_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

DROP TRIGGER IF EXISTS trg_audit_immutable_delete ON audit_log;
CREATE TRIGGER trg_audit_immutable_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

COMMENT ON TABLE audit_log IS
    'Immutable audit trail. Migrated from Oracle AUDIT_LOG. SOX 404 compliant — no UPDATE/DELETE permitted.';
