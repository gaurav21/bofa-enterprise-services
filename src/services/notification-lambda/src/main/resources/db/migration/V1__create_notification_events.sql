-- Migration: Oracle NOTIFICATION_EVENTS -> PostgreSQL notification_events
-- Oracle specifics replaced:
--   CLOB payload       -> JSONB (enables indexed queries on payload fields)
--   Oracle partitioning -> PostgreSQL native range partitioning
--   SYSDATE            -> CURRENT_TIMESTAMP
--   VARCHAR2            -> VARCHAR (standard SQL)
--   NUMBER              -> NUMERIC / BIGINT
--
-- Data residency: us-east-1 (RDS instance constraint)
-- Encryption at rest: AES-256 via KMS (RDS instance-level)
-- Retention: 7 years per GLBA/SOX (enforced by partition management)

CREATE TABLE IF NOT EXISTS notification_events (
    notification_id  VARCHAR(36)   NOT NULL,
    event_type       VARCHAR(50)   NOT NULL,
    account_id       VARCHAR(20)   NOT NULL,
    payload          JSONB         NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_notification_events PRIMARY KEY (notification_id, created_at)
) PARTITION BY RANGE (created_at);

-- Create partitions for monthly data management (7-year retention)
-- In production, partition creation is automated via pg_partman
CREATE TABLE IF NOT EXISTS notification_events_default
    PARTITION OF notification_events DEFAULT;

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_notification_account_id
    ON notification_events (account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_status
    ON notification_events (status, created_at ASC)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_notification_event_type
    ON notification_events (event_type, created_at DESC);

-- GIN index on JSONB payload for flexible querying
CREATE INDEX IF NOT EXISTS idx_notification_payload
    ON notification_events USING GIN (payload);

-- Comment for documentation
COMMENT ON TABLE notification_events IS
    'Migrated from Oracle NOTIFICATION_EVENTS. Partitioned by month for 7-year retention (GLBA/SOX).';
