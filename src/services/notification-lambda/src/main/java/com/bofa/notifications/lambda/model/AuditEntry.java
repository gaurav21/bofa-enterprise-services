package com.bofa.notifications.lambda.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit log entry for SOX 404 / GLBA compliance.
 * Append-only — never modified or deleted.
 * Retention: 7 years per regulatory requirements.
 */
public final class AuditEntry {

    private final String auditId;
    private final String eventType;
    private final String accountId;
    private final String description;
    private final String referenceId;
    private final Instant createdAt;
    private final String createdBy;
    private final String sourceSystem;

    public AuditEntry(String eventType, String accountId,
                      String description, String referenceId) {
        this.auditId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.accountId = accountId;
        this.description = description;
        this.referenceId = referenceId;
        this.createdAt = Instant.now();
        this.createdBy = "NOTIFICATION_LAMBDA";
        this.sourceSystem = "NOTIFICATION_SVC_V4_LAMBDA";
    }

    public String getAuditId() { return auditId; }
    public String getEventType() { return eventType; }
    public String getAccountId() { return accountId; }
    public String getDescription() { return description; }
    public String getReferenceId() { return referenceId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public String getSourceSystem() { return sourceSystem; }
}
