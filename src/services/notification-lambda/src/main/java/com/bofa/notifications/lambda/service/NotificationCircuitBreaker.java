package com.bofa.notifications.lambda.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Circuit breaker for notification service downstream calls.
 * Prevents cascading failures when downstream services (DB, notification channels) are degraded.
 *
 * Replaces Spring Boot Actuator circuit breaker with Resilience4j.
 * State transitions are logged for Datadog monitoring.
 */
public class NotificationCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(NotificationCircuitBreaker.class);

    private final CircuitBreakerRegistry registry;

    public NotificationCircuitBreaker() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(Duration.ofSeconds(10))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .slidingWindowSize(20)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .build();

        this.registry = CircuitBreakerRegistry.of(defaultConfig);

        // Critical path: stricter thresholds for fraud alerts
        CircuitBreakerConfig fraudConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(30)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(10)
                .build();

        registry.circuitBreaker("fraud-db", fraudConfig);
        registry.circuitBreaker("fraud-channel", fraudConfig);
        registry.circuitBreaker("txn-db", defaultConfig);
        registry.circuitBreaker("balance-db", defaultConfig);
    }

    public <T> T execute(String name, Supplier<T> supplier, Supplier<T> fallback) {
        CircuitBreaker cb = registry.circuitBreaker(name);
        try {
            return CircuitBreaker.decorateSupplier(cb, supplier).get();
        } catch (Exception e) {
            log.warn("Circuit breaker '{}' triggered fallback: state={}, error={}",
                    name, cb.getState(), e.getMessage());
            fallback.get();
            throw new RuntimeException("Circuit breaker '" + name + "' fallback invoked — re-throwing for SQS retry", e);
        }
    }

    public void execute(String name, Runnable action, Runnable fallback) {
        CircuitBreaker cb = registry.circuitBreaker(name);
        try {
            CircuitBreaker.decorateRunnable(cb, action).run();
        } catch (Exception e) {
            log.warn("Circuit breaker '{}' triggered fallback: state={}, error={}",
                    name, cb.getState(), e.getMessage());
            fallback.run();
            throw new RuntimeException("Circuit breaker '" + name + "' fallback invoked — re-throwing for SQS retry", e);
        }
    }

    public String getState(String name) {
        return registry.circuitBreaker(name).getState().name();
    }
}
