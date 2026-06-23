package com.bofa.notifications.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ensures message ordering guarantees per account.
 * IBM MQ provides FIFO within a single queue, but with concurrent consumers
 * we need application-level ordering per account ID using sequence numbers.
 * 
 * Critical for fraud alerts: a "fraud cleared" message must not arrive
 * before the initial "fraud detected" message.
 */
@Component
public class MessageOrderGuarantee {

    private static final Logger log = LoggerFactory.getLogger(MessageOrderGuarantee.class);

    private final ConcurrentHashMap<String, AtomicLong> lastProcessedSequence = 
            new ConcurrentHashMap<>();

    /**
     * Check if a message can be processed based on its sequence number.
     * Returns true if the message is the next expected in sequence.
     */
    public boolean canProcess(String accountId, long sequenceNumber) {
        if (sequenceNumber < 0) {
            // Legacy messages without sequence numbers - always process
            return true;
        }

        AtomicLong lastSeq = lastProcessedSequence.computeIfAbsent(
                accountId, k -> new AtomicLong(-1));

        long expected = lastSeq.get() + 1;
        if (sequenceNumber < expected) {
            log.debug("Duplicate message: account={}, seq={}, lastProcessed={}",
                    accountId, sequenceNumber, lastSeq.get());
            return false;
        }
        if (sequenceNumber > expected) {
            log.warn("Gap detected: account={}, expected={}, got={}",
                    accountId, expected, sequenceNumber);
            return false; // Will be redelivered after gap is filled
        }
        return true;
    }

    /**
     * Mark a sequence number as successfully processed.
     */
    public void markProcessed(String accountId, long sequenceNumber) {
        if (sequenceNumber < 0) return;
        lastProcessedSequence.computeIfAbsent(accountId, k -> new AtomicLong(-1))
                .set(sequenceNumber);
    }

    /**
     * Get the last processed sequence number for an account.
     */
    public long getLastProcessed(String accountId) {
        AtomicLong seq = lastProcessedSequence.get(accountId);
        return seq != null ? seq.get() : -1;
    }
}
