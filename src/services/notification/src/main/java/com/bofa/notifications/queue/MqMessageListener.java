package com.bofa.notifications.queue;

/**
 * DEPRECATED: IBM MQ JMS listener has been replaced by SQS FIFO Lambda triggers.
 *
 * Migration mapping:
 *   - @JmsListener(BOFA.NOTIFICATIONS.QUEUE) -> Lambda SQS event source mapping
 *   - TextMessage parsing                     -> SQSEvent.SQSMessage.getBody()
 *   - JMSMessageID                           -> SQS MessageId
 *   - MQ redelivery                          -> SQS visibility timeout + DLQ
 *   - Concurrent JMS consumers (5-20)        -> Lambda concurrency (per-group)
 *
 * See:
 *   - lambda/FraudAlertHandler.java for fraud alert processing
 *   - lambda/TransactionConfirmHandler.java for transaction confirmations
 *   - lambda/BalanceWarningHandler.java for balance warnings
 *   - queue/sqs/SqsMessageProcessor.java for shared message routing logic
 *
 * @deprecated Replaced by Lambda handlers and SqsMessageProcessor
 */
@Deprecated(since = "4.0.0", forRemoval = true)
public class MqMessageListener {
    // Intentionally empty — IBM MQ JMS dependencies removed from pom.xml
    // This class is retained as migration documentation
}
