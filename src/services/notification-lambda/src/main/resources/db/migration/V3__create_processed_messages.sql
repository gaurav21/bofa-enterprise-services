-- Deduplication table for exactly-once message processing.
-- Replaces IBM MQ XA transaction semantics with application-level idempotency.
--
-- SQS FIFO provides 5-minute deduplication; this table extends that to
-- guarantee idempotency across the full message retention period.

CREATE TABLE IF NOT EXISTS processed_messages (
    message_id   VARCHAR(100)  NOT NULL,
    event_type   VARCHAR(50)   NOT NULL,
    account_id   VARCHAR(20)   NOT NULL,
    processed_at TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_processed_messages PRIMARY KEY (message_id)
);

CREATE INDEX IF NOT EXISTS idx_processed_messages_account
    ON processed_messages (account_id, processed_at DESC);

-- Auto-cleanup: remove entries older than 14 days (matches SQS retention)
-- In production, run as a scheduled Lambda or pg_cron job
COMMENT ON TABLE processed_messages IS
    'Message deduplication table. Entries older than 14 days can be safely purged.';
