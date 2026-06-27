package com.bofa.notifications.service;

import com.bofa.notifications.persistence.AuditLogRepository;
import com.bofa.notifications.persistence.NotificationRepository;
import com.bofa.notifications.resilience.CircuitBreakerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * Migration changes:
 *   - Removed Spring @Retryable (Lambda has built-in SQS retry via visibility timeout)
 *   - Added CircuitBreakerService for downstream dependency protection
 *   - @Transactional now backed by PostgreSQL (was Oracle)
 */
@Service
public class FraudNotificationService {

    private static final Logger log = LoggerFactory.getLogger(FraudNotificationService.class);
    private static final int MAX_DELIVERY_SLA_SECONDS = 30;

    private final NotificationRepository notificationRepo;
    private final AuditLogRepository auditLogRepo;
    private final CircuitBreakerService circuitBreaker;

    public FraudNotificationService(NotificationRepository notificationRepo,
                                     AuditLogRepository auditLogRepo,
                                     CircuitBreakerService circuitBreaker) {
        this.notificationRepo = notificationRepo;
        this.auditLogRepo = auditLogRepo;
        this.circuitBreaker = circuitBreaker;
    }

    @Transactional
    public void processFraudAlert(String accountId, String transactionId,
                                   double amount, String merchantName,
                                   String alertSeverity) {
        log.info("Processing fraud alert: account={}, txn={}, amount={}, severity={}",
                accountId, transactionId, amount, alertSeverity);

        String notificationId = UUID.randomUUID().toString();
        Instant detectedAt = Instant.now();

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

        notificationRepo.saveNotification(notificationId, "FRAUD_ALERT",
                accountId, payload.toString(), "PENDING");

        auditLogRepo.logEvent("FRAUD_ALERT_SENT", accountId,
                "Fraud alert dispatched for transaction " + transactionId,
                notificationId);

        circuitBreaker.executeWithCircuitBreaker("fraud-dispatch",
                () -> dispatchMultiChannel(accountId, payload));

        long elapsed = Instant.now().toEpochMilli() - detectedAt.toEpochMilli();
        if (elapsed > MAX_DELIVERY_SLA_SECONDS * 1000L) {
            log.error("FRAUD ALERT SLA BREACH: delivery took {}ms for account {}",
                    elapsed, accountId);
        }
    }

    @Async
    protected void dispatchMultiChannel(String accountId, Map<String, Object> payload) {
        log.info("Dispatching fraud alert to all channels for account {}", accountId);
    }
}
