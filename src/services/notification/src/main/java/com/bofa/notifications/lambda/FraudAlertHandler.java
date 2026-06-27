package com.bofa.notifications.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.queue.sqs.SqsMessageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS Lambda handler for fraud alert notifications.
 *
 * Triggered by SQS FIFO queue: bofa-fraud-alerts-{env}.fifo
 * SLA: Must complete within 30 seconds of message arrival.
 *
 * Provisioned concurrency: 100 (eliminates cold start for critical path).
 * SnapStart enabled for faster initialization.
 *
 * Replaces: MqMessageListener @JmsListener for FRAUD_ALERT events
 */
public class FraudAlertHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger log = LoggerFactory.getLogger(FraudAlertHandler.class);

    private final SqsMessageProcessor processor;

    public FraudAlertHandler() {
        ApplicationContext ctx = LambdaContextInitializer.getContext();
        this.processor = ctx.getBean(SqsMessageProcessor.class);
    }

    FraudAlertHandler(SqsMessageProcessor processor) {
        this.processor = processor;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        log.info("FraudAlertHandler invoked: {} messages, requestId={}",
                event.getRecords().size(), context.getAwsRequestId());

        long startTime = System.currentTimeMillis();

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            Map<String, String> attributes = extractAttributes(message);
            processor.processMessage(
                    message.getMessageId(),
                    message.getBody(),
                    attributes
            );
        }

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > 30_000) {
            log.error("FRAUD ALERT SLA BREACH: Lambda execution took {}ms, requestId={}",
                    elapsed, context.getAwsRequestId());
        }
        log.info("FraudAlertHandler completed in {}ms", elapsed);
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
