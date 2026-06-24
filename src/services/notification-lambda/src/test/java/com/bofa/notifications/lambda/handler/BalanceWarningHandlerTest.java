package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.lambda.messaging.MessageDeduplication;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.service.BalanceWarningService;
import com.bofa.notifications.lambda.service.NotificationCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BalanceWarningHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Mock private BalanceWarningService balanceService;
    @Mock private MessageDeduplication deduplication;
    @Mock private NotificationCircuitBreaker circuitBreaker;
    @Mock private Context context;

    private BalanceWarningHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BalanceWarningHandler(balanceService, deduplication, circuitBreaker);
        when(context.getAwsRequestId()).thenReturn("test-req-bal");
    }

    @Test
    void handleRequest_processesWarning() throws Exception {
        NotificationEvent event = createBalanceEvent();
        SQSEvent sqsEvent = createSqsEvent(event, "bal-001");

        when(deduplication.tryClaimMessage(eq("bal-001"), eq("BALANCE_WARNING"), eq("ACC-555")))
                .thenReturn(true);
        when(circuitBreaker.execute(eq("balance-db"), any(Supplier.class), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<Boolean> supplier = inv.getArgument(1);
                    return supplier.get();
                });
        when(balanceService.sendBalanceWarning(any())).thenReturn(true);

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 1, Cooldown: 0, Duplicates: 0, Total: 1", result);
    }

    @Test
    void handleRequest_cooldownSkipped() throws Exception {
        NotificationEvent event = createBalanceEvent();
        SQSEvent sqsEvent = createSqsEvent(event, "bal-cool");

        when(deduplication.tryClaimMessage(eq("bal-cool"), eq("BALANCE_WARNING"), eq("ACC-555")))
                .thenReturn(true);
        when(circuitBreaker.execute(eq("balance-db"), any(Supplier.class), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<Boolean> supplier = inv.getArgument(1);
                    return supplier.get();
                });
        when(balanceService.sendBalanceWarning(any())).thenReturn(false);

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 0, Cooldown: 1, Duplicates: 0, Total: 1", result);
    }

    @Test
    void handleRequest_duplicateAndProcessedMixed() throws Exception {
        NotificationEvent event1 = createBalanceEvent();
        event1.setAccountId("ACC-001");
        NotificationEvent event2 = createBalanceEvent();
        event2.setAccountId("ACC-002");

        SQSEvent sqsEvent = new SQSEvent();
        SQSEvent.SQSMessage msg1 = new SQSEvent.SQSMessage();
        msg1.setMessageId("m1");
        msg1.setBody(MAPPER.writeValueAsString(event1));
        SQSEvent.SQSMessage msg2 = new SQSEvent.SQSMessage();
        msg2.setMessageId("m2");
        msg2.setBody(MAPPER.writeValueAsString(event2));
        sqsEvent.setRecords(List.of(msg1, msg2));

        when(deduplication.tryClaimMessage(eq("m1"), any(), any())).thenReturn(false);
        when(deduplication.tryClaimMessage(eq("m2"), any(), any())).thenReturn(true);
        when(circuitBreaker.execute(eq("balance-db"), any(Supplier.class), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<Boolean> supplier = inv.getArgument(1);
                    return supplier.get();
                });
        when(balanceService.sendBalanceWarning(any())).thenReturn(true);

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 1, Cooldown: 0, Duplicates: 1, Total: 2", result);
    }

    private NotificationEvent createBalanceEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("BALANCE_WARNING");
        event.setAccountId("ACC-555");
        event.setCurrentBalance(50.00);
        event.setThreshold(100.00);
        event.setWarningType("LOW_BALANCE");
        return event;
    }

    private SQSEvent createSqsEvent(NotificationEvent event, String messageId) throws Exception {
        SQSEvent sqsEvent = new SQSEvent();
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(MAPPER.writeValueAsString(event));
        sqsEvent.setRecords(List.of(message));
        return sqsEvent;
    }
}
