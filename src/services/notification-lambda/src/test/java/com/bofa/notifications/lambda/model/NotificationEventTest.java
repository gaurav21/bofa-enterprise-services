package com.bofa.notifications.lambda.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void toMessageGroupId_returnsAccountId() {
        NotificationEvent event = new NotificationEvent();
        event.setAccountId("ACC-123");

        assertEquals("ACC-123", event.toMessageGroupId());
    }

    @Test
    void toDeduplicationId_usesExplicitIdIfSet() {
        NotificationEvent event = new NotificationEvent();
        event.setDeduplicationId("explicit-dedup-id");

        assertEquals("explicit-dedup-id", event.toDeduplicationId());
    }

    @Test
    void toDeduplicationId_generatesIdForFraudAlert() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("FRAUD_ALERT");
        event.setTransactionId("TXN-999");
        event.setAccountId("ACC-123");

        String dedupId = event.toDeduplicationId();
        assertEquals("FRAUD_ALERT-TXN-999-ACC-123", dedupId);
    }

    @Test
    void toDeduplicationId_generatesContentBasedForOthers() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("TRANSACTION_CONFIRM");
        event.setAccountId("ACC-456");
        event.setSequenceNumber(42);

        String dedupId = event.toDeduplicationId();
        assertTrue(dedupId.startsWith("TRANSACTION_CONFIRM-ACC-456-42-"));
    }

    @Test
    void getMessageGroupId_fallsBackToAccountId() {
        NotificationEvent event = new NotificationEvent();
        event.setAccountId("ACC-789");

        assertEquals("ACC-789", event.getMessageGroupId());
    }

    @Test
    void getMessageGroupId_usesExplicitGroupIdIfSet() {
        NotificationEvent event = new NotificationEvent();
        event.setAccountId("ACC-789");
        event.setMessageGroupId("custom-group");

        assertEquals("custom-group", event.getMessageGroupId());
    }

    @Test
    void jsonSerialization_roundTrip() throws Exception {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("FRAUD_ALERT");
        event.setAccountId("ACC-123");
        event.setTransactionId("TXN-456");
        event.setAmount(999.99);
        event.setSeverity("HIGH");

        String json = MAPPER.writeValueAsString(event);
        NotificationEvent deserialized = MAPPER.readValue(json, NotificationEvent.class);

        assertEquals("FRAUD_ALERT", deserialized.getEventType());
        assertEquals("ACC-123", deserialized.getAccountId());
        assertEquals("TXN-456", deserialized.getTransactionId());
        assertEquals(999.99, deserialized.getAmount());
        assertEquals("HIGH", deserialized.getSeverity());
    }

    @Test
    void jsonDeserialization_ignoresUnknownProperties() throws Exception {
        String json = "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC-123\",\"unknownField\":\"value\"}";

        NotificationEvent event = MAPPER.readValue(json, NotificationEvent.class);

        assertEquals("FRAUD_ALERT", event.getEventType());
        assertEquals("ACC-123", event.getAccountId());
    }
}
