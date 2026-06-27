package com.bofa.notifications.queue.sqs;

import com.bofa.notifications.queue.MessageOrderGuarantee;
import com.bofa.notifications.service.BalanceWarningService;
import com.bofa.notifications.service.FraudNotificationService;
import com.bofa.notifications.service.TransactionConfirmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SQS FIFO message processor replacing MqMessageListener.
 *
 * Migration from IBM MQ:
 *   - JMS TextMessage -> SQS message body (JSON string)
 *   - JMSMessageID    -> SQS MessageId
 *   - MQ correlation   -> SQS MessageGroupId (accountId)
 *   - App-level sequence -> SQS FIFO per-group ordering
 *   - MQ backout queue -> SQS DLQ (after maxReceiveCount=3)
 *   - MQ XA txn        -> Idempotency via MessageDeduplicationId
 *
 * SQS FIFO guarantees per-MessageGroupId ordering, so the
 * MessageOrderGuarantee component is retained as defense-in-depth.
 */
@Component
public class SqsMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqsMessageProcessor.class);

    private final ObjectMapper objectMapper;
    private final FraudNotificationService fraudService;
    private final TransactionConfirmService txnConfirmService;
    private final BalanceWarningService balanceService;
    private final MessageOrderGuarantee orderGuarantee;
    private final IdempotencyGuard idempotencyGuard;

    public SqsMessageProcessor(ObjectMapper objectMapper,
                                FraudNotificationService fraudService,
                                TransactionConfirmService txnConfirmService,
                                BalanceWarningService balanceService,
                                MessageOrderGuarantee orderGuarantee,
                                IdempotencyGuard idempotencyGuard) {
        this.objectMapper = objectMapper;
        this.fraudService = fraudService;
        this.txnConfirmService = txnConfirmService;
        this.balanceService = balanceService;
        this.orderGuarantee = orderGuarantee;
        this.idempotencyGuard = idempotencyGuard;
    }

    /**
     * Process a single SQS FIFO message.
     *
     * @param messageId   SQS message ID
     * @param messageBody JSON message body
     * @param attributes  SQS message attributes
     */
    public void processMessage(String messageId, String messageBody,
                                Map<String, String> attributes) {
        try {
            log.debug("Processing SQS message: id={}", messageId);
            JsonNode json = objectMapper.readTree(messageBody);

            String eventType = json.get("eventType").asText();
            String accountId = json.get("accountId").asText();
            long sequenceNumber = json.has("sequenceNumber")
                    ? json.get("sequenceNumber").asLong() : -1;

            // Idempotency check (replaces MQ XA transaction semantics)
            String deduplicationId = attributes.getOrDefault(
                    "MessageDeduplicationId", messageId);
            if (idempotencyGuard.isDuplicate(deduplicationId)) {
                log.info("Duplicate message detected, skipping: id={}", deduplicationId);
                return;
            }

            // Defense-in-depth ordering check (SQS FIFO handles primary ordering)
            if (!orderGuarantee.canProcess(accountId, sequenceNumber)) {
                log.warn("Out-of-order message: account={}, seq={}. " +
                        "SQS FIFO should prevent this — flagging for review.",
                        accountId, sequenceNumber);
                return;
            }

            routeToService(eventType, accountId, json);

            orderGuarantee.markProcessed(accountId, sequenceNumber);
            idempotencyGuard.markProcessed(deduplicationId);

        } catch (Exception e) {
            log.error("Failed to process SQS message: id={}", messageId, e);
            throw new RuntimeException("SQS message processing failed", e);
        }
    }

    private void routeToService(String eventType, String accountId, JsonNode json) {
        switch (eventType) {
            case "FRAUD_ALERT":
                fraudService.processFraudAlert(
                        accountId,
                        json.get("transactionId").asText(),
                        json.get("amount").asDouble(),
                        json.get("merchantName").asText(),
                        json.get("severity").asText()
                );
                break;
            case "TRANSACTION_CONFIRM":
                txnConfirmService.sendConfirmation(
                        accountId,
                        json.get("transactionId").asText(),
                        json.get("transactionType").asText(),
                        json.get("amount").asDouble(),
                        json.get("currency").asText("USD"),
                        json.get("description").asText()
                );
                break;
            case "BALANCE_WARNING":
                balanceService.sendBalanceWarning(
                        accountId,
                        json.get("currentBalance").asDouble(),
                        json.get("threshold").asDouble(),
                        json.get("warningType").asText()
                );
                break;
            default:
                log.warn("Unknown event type: {}", eventType);
        }
    }
}
