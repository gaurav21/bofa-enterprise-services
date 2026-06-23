package com.bofa.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.retry.annotation.EnableRetry;

/**
 * BofA Notification Service - Mission Critical
 * 
 * Handles fraud alerts, transaction confirmations, balance warnings,
 * and regulatory disclosures via IBM MQ message consumption.
 * 
 * SLA: 99.99% uptime, <30s fraud alert delivery
 * Volume: ~4.2M events/day peak
 * 
 * @since 1.0.0
 * @author Platform Engineering - Notification Squad
 */
@SpringBootApplication
@EnableJms
@EnableAsync
@EnableRetry
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
