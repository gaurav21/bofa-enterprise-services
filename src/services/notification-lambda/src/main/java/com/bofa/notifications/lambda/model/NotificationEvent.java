package com.bofa.notifications.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Canonical notification event model.
 * Compatible with both legacy IBM MQ JSON format and new SQS FIFO messages.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationEvent {

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("accountId")
    private String accountId;

    @JsonProperty("transactionId")
    private String transactionId;

    @JsonProperty("amount")
    private Double amount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("merchantName")
    private String merchantName;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("transactionType")
    private String transactionType;

    @JsonProperty("description")
    private String description;

    @JsonProperty("currentBalance")
    private Double currentBalance;

    @JsonProperty("threshold")
    private Double threshold;

    @JsonProperty("warningType")
    private String warningType;

    @JsonProperty("sequenceNumber")
    private long sequenceNumber = -1;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("messageGroupId")
    private String messageGroupId;

    @JsonProperty("deduplicationId")
    private String deduplicationId;

    public NotificationEvent() {}

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getCurrentBalance() { return currentBalance; }
    public void setCurrentBalance(Double currentBalance) { this.currentBalance = currentBalance; }

    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }

    public String getWarningType() { return warningType; }
    public void setWarningType(String warningType) { this.warningType = warningType; }

    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getMessageGroupId() {
        return messageGroupId != null ? messageGroupId : accountId;
    }
    public void setMessageGroupId(String messageGroupId) { this.messageGroupId = messageGroupId; }

    public String getDeduplicationId() { return deduplicationId; }
    public void setDeduplicationId(String deduplicationId) { this.deduplicationId = deduplicationId; }

    /**
     * Generates the SQS FIFO MessageGroupId from the account ID.
     * This preserves per-account ordering from the legacy IBM MQ implementation.
     */
    public String toMessageGroupId() {
        return accountId;
    }

    /**
     * Generates a deduplication ID for exactly-once processing.
     * For fraud alerts, uses explicit IDs; for others, uses content-based dedup.
     */
    public String toDeduplicationId() {
        if (deduplicationId != null) {
            return deduplicationId;
        }
        if ("FRAUD_ALERT".equals(eventType) && transactionId != null) {
            return eventType + "-" + transactionId + "-" + accountId;
        }
        return eventType + "-" + accountId + "-" + sequenceNumber;
    }
}
