package com.bofa.notifications.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.queue.sqs.SqsMessageProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FraudAlertHandlerTest {

    private SqsMessageProcessor processor;
    private FraudAlertHandler handler;
    private Context context;

    @BeforeEach
    void setUp() {
        processor = mock(SqsMessageProcessor.class);
        handler = new FraudAlertHandler(processor);
        context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("test-request-id");
    }

    @Test
    void handleRequest_singleFraudAlert_processesSuccessfully() {
        SQSEvent event = createSqsEvent("msg-001",
                "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"," +
                "\"transactionId\":\"TXN001\",\"amount\":999.99," +
                "\"merchantName\":\"SuspiciousMerchant\",\"severity\":\"HIGH\"}");

        handler.handleRequest(event, context);

        verify(processor).processMessage(eq("msg-001"), contains("FRAUD_ALERT"), anyMap());
    }

    @Test
    void handleRequest_multipleFraudAlerts_processesAll() {
        SQSEvent event = new SQSEvent();
        List<SQSEvent.SQSMessage> messages = List.of(
                createMessage("msg-001", "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"," +
                        "\"transactionId\":\"TXN001\",\"amount\":100.0," +
                        "\"merchantName\":\"Merchant1\",\"severity\":\"HIGH\"}"),
                createMessage("msg-002", "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC002\"," +
                        "\"transactionId\":\"TXN002\",\"amount\":200.0," +
                        "\"merchantName\":\"Merchant2\",\"severity\":\"CRITICAL\"}")
        );
        event.setRecords(messages);

        handler.handleRequest(event, context);

        verify(processor, times(2)).processMessage(anyString(), anyString(), anyMap());
    }

    @Test
    void handleRequest_emptyBatch_completesWithoutError() {
        SQSEvent event = new SQSEvent();
        event.setRecords(Collections.emptyList());

        assertDoesNotThrow(() -> handler.handleRequest(event, context));
        verifyNoInteractions(processor);
    }

    @Test
    void handleRequest_processorThrows_propagatesException() {
        SQSEvent event = createSqsEvent("msg-001", "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"}");
        doThrow(new RuntimeException("DB unavailable")).when(processor)
                .processMessage(anyString(), anyString(), anyMap());

        assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
    }

    @Test
    void handleRequest_extractsMessageAttributes() {
        SQSEvent event = new SQSEvent();
        SQSEvent.SQSMessage message = createMessage("msg-001",
                "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"}");

        SQSEvent.MessageAttribute dedup = new SQSEvent.MessageAttribute();
        dedup.setStringValue("dedup-123");
        message.setMessageAttributes(Map.of("MessageDeduplicationId", dedup));

        Map<String, String> systemAttrs = new HashMap<>();
        systemAttrs.put("MessageGroupId", "ACC001");
        message.setAttributes(systemAttrs);

        event.setRecords(List.of(message));

        handler.handleRequest(event, context);

        ArgumentCaptor<Map<String, String>> attrsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(processor).processMessage(eq("msg-001"), anyString(), attrsCaptor.capture());

        Map<String, String> capturedAttrs = attrsCaptor.getValue();
        assertEquals("ACC001", capturedAttrs.get("MessageGroupId"));
        assertEquals("dedup-123", capturedAttrs.get("MessageDeduplicationId"));
    }

    private SQSEvent createSqsEvent(String messageId, String body) {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(createMessage(messageId, body)));
        return event;
    }

    private SQSEvent.SQSMessage createMessage(String messageId, String body) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(body);
        message.setAttributes(new HashMap<>());
        message.setMessageAttributes(new HashMap<>());
        return message;
    }
}
