package com.bofa.notifications.config;

/**
 * DEPRECATED: IBM MQ configuration has been replaced by SQS FIFO.
 *
 * Migration mapping:
 *   - BOFA.NOTIFICATIONS.QUEUE    -> bofa-fraud-alerts-{env}.fifo
 *                                     bofa-txn-confirmations-{env}.fifo
 *                                     bofa-balance-warnings-{env}.fifo
 *   - BOFA.NOTIFICATIONS.DLQ     -> bofa-fraud-alerts-dlq-{env}.fifo
 *   - BOFA.FRAUD.PRIORITY.QUEUE  -> bofa-fraud-alerts-{env}.fifo (provisioned concurrency)
 *
 * IBM MQ connection factory, JMS listener container factory,
 * and all MQ-specific configuration have been removed.
 *
 * See:
 *   - config/aws/SqsConfig.java for SQS client configuration
 *   - queue/sqs/SqsMessageProcessor.java for message processing
 *   - lambda/*Handler.java for SQS-triggered Lambda entry points
 *
 * @deprecated Replaced by SQS FIFO configuration in config/aws/SqsConfig.java
 */
@Deprecated(since = "4.0.0", forRemoval = true)
public class MqConfig {
    // Intentionally empty — IBM MQ dependencies removed from pom.xml
    // This class is retained as migration documentation
}
