package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.bofa.notifications.lambda.config.CognitoAuthConfig;
import com.bofa.notifications.lambda.messaging.SqsFifoPublisher;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresAuditLogRepository;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API Gateway Lambda handler for REST endpoints.
 * Replaces Spring Boot @RestController endpoints.
 *
 * Endpoints:
 *   POST /api/notifications       -> Submit new notification (publishes to SQS FIFO)
 *   GET  /api/notifications/{id}  -> Query notification by account
 *   GET  /api/audit/{accountId}   -> Query audit trail
 *   GET  /health                  -> Health check (no auth required)
 *
 * Auth: Cognito JWT (replaces LDAP basic auth)
 *   - /api/notifications/** requires scope: notification-service/write
 *   - /api/admin/**          requires scope: notification-service/admin
 *   - /health               no auth required
 */
public class NotificationApiHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger log = LoggerFactory.getLogger(NotificationApiHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final CognitoAuthConfig authConfig;
    private final SqsFifoPublisher sqsPublisher;
    private final PostgresNotificationRepository notificationRepo;
    private final PostgresAuditLogRepository auditLogRepo;

    public NotificationApiHandler() {
        this.authConfig = new CognitoAuthConfig();
        this.sqsPublisher = new SqsFifoPublisher();
        this.notificationRepo = new PostgresNotificationRepository();
        this.auditLogRepo = new PostgresAuditLogRepository();
    }

    public NotificationApiHandler(CognitoAuthConfig authConfig,
                                   SqsFifoPublisher sqsPublisher,
                                   PostgresNotificationRepository notificationRepo,
                                   PostgresAuditLogRepository auditLogRepo) {
        this.authConfig = authConfig;
        this.sqsPublisher = sqsPublisher;
        this.notificationRepo = notificationRepo;
        this.auditLogRepo = auditLogRepo;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent request, Context context) {

        String path = request.getPath();
        String method = request.getHttpMethod();

        log.info("API request: {} {} requestId={}", method, path, context.getAwsRequestId());

        if ("/health".equals(path)) {
            return jsonResponse(200, Map.of(
                    "status", "UP",
                    "service", "notification-lambda",
                    "timestamp", Instant.now().toString()));
        }

        String authHeader = request.getHeaders() != null
                ? request.getHeaders().get("Authorization")
                : null;

        if (path.startsWith("/api/notifications") && "POST".equals(method)) {
            if (!authConfig.hasScope(authHeader, "notification-service/write")) {
                return jsonResponse(403, Map.of("error", "Insufficient scope: notification-service/write required"));
            }
            return handleSubmitNotification(request);
        }

        if (path.startsWith("/api/notifications") && "GET".equals(method)) {
            if (!authConfig.hasScope(authHeader, "notification-service/write")) {
                return jsonResponse(403, Map.of("error", "Insufficient scope"));
            }
            return handleQueryNotifications(request);
        }

        if (path.startsWith("/api/audit") && "GET".equals(method)) {
            if (!authConfig.hasScope(authHeader, "audit-service/read")) {
                return jsonResponse(403, Map.of("error", "Insufficient scope: audit-service/read required"));
            }
            return handleQueryAuditTrail(request);
        }

        return jsonResponse(404, Map.of("error", "Not found: " + method + " " + path));
    }

    private APIGatewayProxyResponseEvent handleSubmitNotification(
            APIGatewayProxyRequestEvent request) {
        try {
            NotificationEvent event = MAPPER.readValue(request.getBody(), NotificationEvent.class);

            if (event.getEventType() == null || event.getAccountId() == null) {
                return jsonResponse(400, Map.of("error", "eventType and accountId are required"));
            }

            String messageId = sqsPublisher.publish(event);
            return jsonResponse(202, Map.of(
                    "messageId", messageId,
                    "status", "ACCEPTED",
                    "eventType", event.getEventType()));

        } catch (Exception e) {
            log.error("Failed to submit notification", e);
            return jsonResponse(500, Map.of("error", "Internal server error"));
        }
    }

    private APIGatewayProxyResponseEvent handleQueryNotifications(
            APIGatewayProxyRequestEvent request) {
        try {
            Map<String, String> params = request.getQueryStringParameters();
            if (params == null || !params.containsKey("accountId")) {
                return jsonResponse(400, Map.of("error", "accountId query parameter required"));
            }

            String accountId = params.get("accountId");
            int limit = params.containsKey("limit")
                    ? Math.min(Math.max(Integer.parseInt(params.get("limit")), 1), 500) : 50;

            List<Map<String, Object>> results = notificationRepo.findByAccountId(accountId, limit);
            return jsonResponse(200, Map.of("notifications", results, "count", results.size()));

        } catch (Exception e) {
            log.error("Failed to query notifications", e);
            return jsonResponse(500, Map.of("error", "Internal server error"));
        }
    }

    private APIGatewayProxyResponseEvent handleQueryAuditTrail(
            APIGatewayProxyRequestEvent request) {
        try {
            Map<String, String> params = request.getQueryStringParameters();
            Map<String, String> pathParams = request.getPathParameters();

            String accountId = pathParams != null ? pathParams.get("accountId") : null;
            if (accountId == null && params != null) {
                accountId = params.get("accountId");
            }

            if (accountId == null) {
                return jsonResponse(400, Map.of("error", "accountId is required"));
            }

            String fromStr = params != null ? params.get("from") : null;
            String toStr = params != null ? params.get("to") : null;

            Instant from = fromStr != null
                    ? Instant.parse(fromStr)
                    : Instant.now().minusSeconds(86400);
            Instant to = toStr != null
                    ? Instant.parse(toStr)
                    : Instant.now();

            List<Map<String, Object>> trail = auditLogRepo.getAuditTrail(accountId, from, to);
            return jsonResponse(200, Map.of("auditTrail", trail, "count", trail.size()));

        } catch (Exception e) {
            log.error("Failed to query audit trail", e);
            return jsonResponse(500, Map.of("error", "Internal server error"));
        }
    }

    private APIGatewayProxyResponseEvent jsonResponse(int statusCode, Object body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setHeaders(Map.of(
                "Content-Type", "application/json",
                "X-Content-Type-Options", "nosniff",
                "Strict-Transport-Security", "max-age=31536000; includeSubDomains"));
        try {
            response.setBody(MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            response.setBody("{\"error\":\"Serialization failed\"}");
        }
        return response;
    }
}
