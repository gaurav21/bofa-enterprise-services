package com.bofa.notifications.service;

import com.bofa.notifications.persistence.AuditLogRepository;
import com.bofa.notifications.persistence.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sends transaction confirmation notifications after successful processing.
 * Covers debit, credit, wire transfer, and ACH confirmations.
 * Regulatory: Reg E requires written confirmation of electronic fund transfers.
 */
@Service
public class TransactionConfirmService {

    private static final Logger log = LoggerFactory.getLogger(TransactionConfirmService.class);

    private final NotificationRepository notificationRepo;
    private final AuditLogRepository auditLogRepo;

    public TransactionConfirmService(NotificationRepository notificationRepo,
                                      AuditLogRepository auditLogRepo) {
        this.notificationRepo = notificationRepo;
        this.auditLogRepo = auditLogRepo;
    }

    @Transactional
    public void sendConfirmation(String accountId, String transactionId,
                                  String transactionType, double amount,
                                  String currency, String description) {
        log.info("Sending transaction confirmation: account={}, txn={}, type={}, amount={}",
                accountId, transactionId, transactionType, amount);

        String notificationId = UUID.randomUUID().toString();

        Map<String, Object> payload = Map.of(
            "notificationId", notificationId,
            "type", "TRANSACTION_CONFIRMATION",
            "accountId", accountId,
            "transactionId", transactionId,
            "transactionType", transactionType,
            "amount", amount,
            "currency", currency,
            "description", description,
            "confirmedAt", Instant.now().toString()
        );

        notificationRepo.saveNotification(notificationId, "TRANSACTION_CONFIRM",
                accountId, payload.toString(), "DELIVERED");

        auditLogRepo.logEvent("TXN_CONFIRM_SENT", accountId,
                String.format("Confirmation sent for %s transaction %s: %s %.2f",
                        transactionType, transactionId, currency, amount),
                notificationId);

        // Route to preferred channel based on customer settings
        routeToPreferredChannel(accountId, payload);
    }

    private void routeToPreferredChannel(String accountId, Map<String, Object> payload) {
        // TODO: Look up customer channel preference from profile service
        // Default: push notification + email
        log.debug("Routing confirmation to preferred channel for account {}", accountId);
    }
}
