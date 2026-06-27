package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.bofa.notifications.lambda.config.CognitoAuthConfig;
import com.bofa.notifications.lambda.messaging.SqsFifoPublisher;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresAuditLogRepository;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationApiHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Mock private CognitoAuthConfig authConfig;
    @Mock private SqsFifoPublisher sqsPublisher;
    @Mock private PostgresNotificationRepository notificationRepo;
    @Mock private PostgresAuditLogRepository auditLogRepo;
    @Mock private Context context;

    private NotificationApiHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NotificationApiHandler(authConfig, sqsPublisher, notificationRepo, auditLogRepo);
        when(context.getAwsRequestId()).thenReturn("test-api-req");
    }

    @Test
    void healthCheck_returnsUp() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/health");
        request.setHttpMethod("GET");

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"status\":\"UP\""));
        assertTrue(response.getBody().contains("notification-lambda"));
    }

    @Test
    void healthCheck_hasSecurityHeaders() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/health");
        request.setHttpMethod("GET");

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        assertEquals("nosniff", response.getHeaders().get("X-Content-Type-Options"));
        assertNotNull(response.getHeaders().get("Strict-Transport-Security"));
    }

    @Test
    void submitNotification_accepted() throws Exception {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("FRAUD_ALERT");
        event.setAccountId("ACC-123");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/notifications");
        request.setHttpMethod("POST");
        request.setHeaders(Map.of("Authorization", "Bearer valid-token"));
        request.setBody(MAPPER.writeValueAsString(event));

        when(authConfig.hasScope("Bearer valid-token", "notification-service/write")).thenReturn(true);
        when(sqsPublisher.publish(any())).thenReturn("sqs-msg-id-001");

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(202, response.getStatusCode());
        assertTrue(response.getBody().contains("sqs-msg-id-001"));
        assertTrue(response.getBody().contains("ACCEPTED"));
    }

    @Test
    void submitNotification_forbidden() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/notifications");
        request.setHttpMethod("POST");
        request.setHeaders(Map.of("Authorization", "Bearer bad-token"));

        when(authConfig.hasScope("Bearer bad-token", "notification-service/write")).thenReturn(false);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(403, response.getStatusCode());
    }

    @Test
    void submitNotification_missingRequiredFields() throws Exception {
        NotificationEvent event = new NotificationEvent();
        // Missing eventType and accountId

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/notifications");
        request.setHttpMethod("POST");
        request.setHeaders(Map.of("Authorization", "Bearer valid-token"));
        request.setBody(MAPPER.writeValueAsString(event));

        when(authConfig.hasScope("Bearer valid-token", "notification-service/write")).thenReturn(true);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("eventType and accountId are required"));
    }

    @Test
    void queryNotifications_returnsResults() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/notifications");
        request.setHttpMethod("GET");
        request.setHeaders(Map.of("Authorization", "Bearer valid-token"));
        request.setQueryStringParameters(Map.of("accountId", "ACC-123"));

        when(authConfig.hasScope("Bearer valid-token", "notification-service/write")).thenReturn(true);
        when(notificationRepo.findByAccountId("ACC-123", 50)).thenReturn(List.of(
                Map.of("notification_id", "n1", "event_type", "FRAUD_ALERT")
        ));

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"count\":1"));
    }

    @Test
    void queryNotifications_missingAccountId() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/notifications");
        request.setHttpMethod("GET");
        request.setHeaders(Map.of("Authorization", "Bearer valid-token"));

        when(authConfig.hasScope("Bearer valid-token", "notification-service/write")).thenReturn(true);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void queryAuditTrail_success() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/audit/ACC-123");
        request.setHttpMethod("GET");
        request.setHeaders(Map.of("Authorization", "Bearer audit-token"));
        request.setPathParameters(Map.of("accountId", "ACC-123"));
        request.setQueryStringParameters(new HashMap<>());

        when(authConfig.hasScope("Bearer audit-token", "audit-service/read")).thenReturn(true);
        when(auditLogRepo.getAuditTrail(eq("ACC-123"), any(), any())).thenReturn(List.of(
                Map.of("audit_id", "a1", "event_type", "FRAUD_ALERT_SENT")
        ));

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("auditTrail"));
    }

    @Test
    void queryAuditTrail_forbidden() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/audit/ACC-123");
        request.setHttpMethod("GET");
        request.setHeaders(Map.of("Authorization", "Bearer no-scope"));

        when(authConfig.hasScope("Bearer no-scope", "audit-service/read")).thenReturn(false);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(403, response.getStatusCode());
    }

    @Test
    void unknownRoute_returns404() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setPath("/api/unknown");
        request.setHttpMethod("GET");
        request.setHeaders(Map.of("Authorization", "Bearer token"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(404, response.getStatusCode());
    }
}
