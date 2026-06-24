package com.bofa.notifications.lambda.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationCircuitBreakerTest {

    private NotificationCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = new NotificationCircuitBreaker();
    }

    @Test
    void execute_returnsSupplierResult() {
        String result = circuitBreaker.execute("fraud-db",
                () -> "success",
                () -> "fallback");

        assertEquals("success", result);
    }

    @Test
    void execute_returnsFallbackOnException() {
        String result = circuitBreaker.execute("fraud-db",
                () -> { throw new RuntimeException("DB down"); },
                () -> "fallback-value");

        assertEquals("fallback-value", result);
    }

    @Test
    void getState_initiallyClosedForKnownBreakers() {
        assertEquals("CLOSED", circuitBreaker.getState("fraud-db"));
        assertEquals("CLOSED", circuitBreaker.getState("fraud-channel"));
        assertEquals("CLOSED", circuitBreaker.getState("txn-db"));
        assertEquals("CLOSED", circuitBreaker.getState("balance-db"));
    }

    @Test
    void execute_runnable_completesSuccessfully() {
        boolean[] executed = {false};

        circuitBreaker.execute("txn-db",
                () -> executed[0] = true,
                () -> {});

        assertTrue(executed[0]);
    }

    @Test
    void execute_runnable_callsFallbackOnException() {
        boolean[] fallbackCalled = {false};

        circuitBreaker.execute("txn-db",
                () -> { throw new RuntimeException("fail"); },
                () -> fallbackCalled[0] = true);

        assertTrue(fallbackCalled[0]);
    }
}
