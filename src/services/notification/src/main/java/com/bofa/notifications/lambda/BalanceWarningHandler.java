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
 * AWS Lambda handler for balance warning notifications.
 *
 * Triggered by SQS FIFO queue: bofa-balance-warnings-{env}.fifo
 * Content-based deduplication enabled on this queue.
 *
 * Replaces: MqMessageListener @JmsListener for BALANCE_WARNING events
 */
public class BalanceWarningHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger log = LoggerFactory.getLogger(BalanceWarningHandler.class);

    private final SqsMessageProcessor processor;

    public BalanceWarningHandler() {
        ApplicationContext ctx = LambdaContextInitializer.getContext();
        this.processor = ctx.getBean(SqsMessageProcessor.class);
    }

    BalanceWarningHandler(SqsMessageProcessor processor) {
        this.processor = processor;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        log.info("BalanceWarningHandler invoked: {} messages, requestId={}",
                event.getRecords().size(), context.getAwsRequestId());

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            Map<String, String> attributes = extractAttributes(message);
            processor.processMessage(
                    message.getMessageId(),
                    message.getBody(),
                    attributes
            );
        }

        log.info("BalanceWarningHandler completed, requestId={}", context.getAwsRequestId());
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
