package com.bofa.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * BofA Notification Service — AWS Lambda Edition
 *
 * Migrated from Spring Boot 2.7 + IBM MQ + Oracle + LDAP
 * to AWS Lambda + SQS FIFO + RDS PostgreSQL + Cognito.
 *
 * SLA: 99.99% uptime, <30s fraud alert delivery
 * Volume: ~4.2M events/day peak
 *
 * Lambda handlers are the primary entry points; this class
 * bootstraps the Spring context for dependency injection
 * during Lambda cold start (SnapStart optimized).
 */
@SpringBootApplication
@EnableAsync
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
