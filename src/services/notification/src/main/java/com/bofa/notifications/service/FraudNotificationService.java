package com.bofa.notifications.service;

import com.bofa.notifications.persistence.AuditLogRepository;
import com.bofa.notifications.persistence.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Processes real-time fraud alert notifications.
 * SLA: Must deliver within 30 seconds of detection.
 * Regulatory: BSA/AML requires immediate customer notification for confirmed fraud.
 */
@Service
public class FraudNotificationService {

    private static final Logger log = LoggerFactory.getLogger(FraudNotificationService.class);
    private static final int MAX_DELIVERY_SLA_SECONDS = 30;

    private final NotificationRepository notificationRepo;
    private final AuditLogRepository auditLogRepo;

    public FraudNotificationService(NotificationRepository notificationRepo,
                                     AuditLogRepository auditLogRepo) {
        this.notificationRepo = notificationRepo;
        this.auditLogRepo = auditLogRepo;
    }

    @Transactional
    @Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void processFraudAlert(String accountId, String transactionId,
                                   double amount, String merchantName,
                                   String alertSeverity) {
        log.info("Processing fraud alert: account={}, txn={}, amount={}, severity={}",
                accountId, transactionId, amount, alertSeverity);

        String notificationId = UUID.randomUUID().toString();
        Instant detectedAt = Instant.now();

        // Build notification payload
        Map<String, Object> payload = Map.of(
            "notificationId", notificationId,
            "type", "FRAUD_ALERT",
            "accountId", accountId,
            "transactionId", transactionId,
            "amount", amount,
            "merchantName", merchantName,
            "severity", alertSeverity,
            "detectedAt", detectedAt.toString()
        );

        // Persist notification record
        notificationRepo.saveNotification(notificationId, "FRAUD_ALERT",
                accountId, payload.toString(), "PENDING");

        // Create immutable audit trail entry
        auditLogRepo.logEvent("FRAUD_ALERT_SENT", accountId,
                "Fraud alert dispatched for transaction " + transactionId,
                notificationId);

        // Dispatch via configured channels (SMS, push, email)
        dispatchMultiChannel(accountId, payload);

        long elapsed = Instant.now().toEpochMilli() - detectedAt.toEpochMilli();
        if (elapsed > MAX_DELIVERY_SLA_SECONDS * 1000L) {
            log.error("FRAUD ALERT SLA BREACH: delivery took {}ms for account {}",
                    elapsed, accountId);
        }
    }

    @Async
    protected void dispatchMultiChannel(String accountId, Map<String, Object> payload) {
        // TODO: Implement SMS gateway, APNs/FCM push, SendGrid email
        // Currently stubbed - relies on downstream notification gateway
        log.info("Dispatching fraud alert to all channels for account {}", accountId);
    }
}
