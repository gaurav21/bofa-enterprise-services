package com.bofa.notifications.queue;

import com.bofa.notifications.service.BalanceWarningService;
import com.bofa.notifications.service.FraudNotificationService;
import com.bofa.notifications.service.TransactionConfirmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import javax.jms.TextMessage;

/**
 * IBM MQ message consumer for notification events.
 * Listens on BOFA.NOTIFICATIONS.QUEUE and routes to appropriate service.
 * Message format: JSON with "eventType" discriminator field.
 */
@Component
public class MqMessageListener {

    private static final Logger log = LoggerFactory.getLogger(MqMessageListener.class);

    private final ObjectMapper objectMapper;
    private final FraudNotificationService fraudService;
    private final TransactionConfirmService txnConfirmService;
    private final BalanceWarningService balanceService;
    private final MessageOrderGuarantee orderGuarantee;

    public MqMessageListener(ObjectMapper objectMapper,
                              FraudNotificationService fraudService,
                              TransactionConfirmService txnConfirmService,
                              BalanceWarningService balanceService,
                              MessageOrderGuarantee orderGuarantee) {
        this.objectMapper = objectMapper;
        this.fraudService = fraudService;
        this.txnConfirmService = txnConfirmService;
        this.balanceService = balanceService;
        this.orderGuarantee = orderGuarantee;
    }

    @JmsListener(destination = "${ibm.mq.queue.notifications}",
                 containerFactory = "jmsListenerContainerFactory")
    public void onMessage(TextMessage message) {
        try {
            String body = message.getText();
            String messageId = message.getJMSMessageID();
            log.debug("Received MQ message: id={}", messageId);

            JsonNode json = objectMapper.readTree(body);
            String eventType = json.get("eventType").asText();
            long sequenceNumber = json.has("sequenceNumber")
                    ? json.get("sequenceNumber").asLong() : -1;

            // Enforce message ordering per account
            String accountId = json.get("accountId").asText();
            if (!orderGuarantee.canProcess(accountId, sequenceNumber)) {
                log.warn("Out-of-order message detected: account={}, seq={}", 
                        accountId, sequenceNumber);
                return; // Will be redelivered
            }

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

            orderGuarantee.markProcessed(accountId, sequenceNumber);

        } catch (Exception e) {
            log.error("Failed to process MQ message", e);
            throw new RuntimeException("Message processing failed", e);
        }
    }
}
