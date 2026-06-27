package com.bofa.notifications.config.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Amazon SQS client configuration replacing IBM MQ.
 *
 * Migration from IBM MQ:
 *   - BOFA.NOTIFICATIONS.QUEUE      -> bofa-fraud-alerts-{env}.fifo (per-type split)
 *   - BOFA.NOTIFICATIONS.DLQ        -> bofa-fraud-alerts-dlq-{env}.fifo
 *   - BOFA.FRAUD.PRIORITY.QUEUE     -> bofa-fraud-alerts-{env}.fifo (collapsed)
 *
 * FIFO guarantees:
 *   - MessageGroupId = accountId (preserves per-account ordering)
 *   - MessageDeduplicationId = transactionId or explicit UUID
 *   - High-throughput mode with perMessageGroupId ordering
 */
@Configuration
@Profile("aws")
public class SqsConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
