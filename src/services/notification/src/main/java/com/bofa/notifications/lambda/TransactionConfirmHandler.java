package com.bofa.notifications.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.queue.sqs.SqsMessageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS Lambda handler for transaction confirmation notifications.
 *
 * Triggered by SQS FIFO queue: bofa-txn-confirmations-{env}.fifo
 * Content-based deduplication enabled on this queue.
 *
 * Replaces: MqMessageListener @JmsListener for TRANSACTION_CONFIRM events
 */
public class TransactionConfirmHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger log = LoggerFactory.getLogger(TransactionConfirmHandler.class);

    private final SqsMessageProcessor processor;

    public TransactionConfirmHandler() {
        ApplicationContext ctx = LambdaContextInitializer.getContext();
        this.processor = ctx.getBean(SqsMessageProcessor.class);
    }

    TransactionConfirmHandler(SqsMessageProcessor processor) {
        this.processor = processor;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        log.info("TransactionConfirmHandler invoked: {} messages, requestId={}",
                event.getRecords().size(), context.getAwsRequestId());

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            Map<String, String> attributes = extractAttributes(message);
            processor.processMessage(
                    message.getMessageId(),
                    message.getBody(),
                    attributes
            );
        }

        log.info("TransactionConfirmHandler completed, requestId={}", context.getAwsRequestId());
        return null;
    }

    private Map<String, String> extractAttributes(SQSEvent.SQSMessage message) {
        Map<String, String> attrs = new HashMap<>();
        if (message.getAttributes() != null) {
            attrs.putAll(message.getAttributes());
        }
        if (message.getMessageAttributes() != null) {
            message.getMessageAttributes().forEach((key, attr) ->
                    attrs.put(key, attr.getStringValue()));
        }
        return attrs;
    }
}
