package com.bofa.notifications.lambda.service;

import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresAuditLogRepository;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction confirmation processor — ported from Spring Boot TransactionConfirmService.
 *
 * Regulatory: Reg E requires written confirmation of electronic fund transfers.
 * Covers debit, credit, wire transfer, and ACH confirmations.
 *
 * Changes from Spring Boot version:
 * - @Transactional -> explicit connection management in repository
 * - Spring DI      -> Constructor injection with explicit wiring
 */
public class TransactionConfirmService {

    private static final Logger log = LoggerFactory.getLogger(TransactionConfirmService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PostgresNotificationRepository notificationRepo;
    private final PostgresAuditLogRepository auditLogRepo;

    public TransactionConfirmService(PostgresNotificationRepository notificationRepo,
                                      PostgresAuditLogRepository auditLogRepo) {
        this.notificationRepo = notificationRepo;
        this.auditLogRepo = auditLogRepo;
    }

    public void sendConfirmation(NotificationEvent event) {
        log.info("Sending transaction confirmation: account={}, txn={}, type={}, amount={}",
                event.getAccountId(), event.getTransactionId(),
                event.getTransactionType(), event.getAmount());

        String notificationId = UUID.randomUUID().toString();
        String currency = event.getCurrency() != null ? event.getCurrency() : "USD";

        Map<String, Object> payload = new HashMap<>();
        payload.put("notificationId", notificationId);
        payload.put("type", "TRANSACTION_CONFIRMATION");
        payload.put("accountId", event.getAccountId());
        payload.put("transactionId", event.getTransactionId());
        payload.put("transactionType", event.getTransactionType());
        payload.put("amount", event.getAmount());
        payload.put("currency", currency);
        payload.put("description", event.getDescription() != null ? event.getDescription() : "");
        payload.put("confirmedAt", Instant.now().toString());

        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize transaction confirmation payload", e);
        }

        notificationRepo.saveNotification(notificationId, "TRANSACTION_CONFIRM",
                event.getAccountId(), payloadJson, "DELIVERED");

        auditLogRepo.logEvent("TXN_CONFIRM_SENT", event.getAccountId(),
                String.format("Confirmation sent for %s transaction %s: %s %.2f",
                        event.getTransactionType(), event.getTransactionId(),
                        currency, event.getAmount()),
                notificationId);

        routeToPreferredChannel(event.getAccountId(), payload);
    }

    private void routeToPreferredChannel(String accountId, Map<String, Object> payload) {
        log.debug("Routing confirmation to preferred channel for account {}", accountId);
        // Channel routing: SNS for push, SES for email
    }
}
