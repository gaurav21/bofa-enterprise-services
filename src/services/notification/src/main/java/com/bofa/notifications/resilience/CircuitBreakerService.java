package com.bofa.notifications.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Circuit breaker patterns for downstream dependency protection.
 *
 * Replaces Spring @Retryable with Resilience4j circuit breakers
 * for Lambda-appropriate failure handling. Lambda + SQS provides
 * automatic retry via visibility timeout; circuit breakers protect
 * against cascading failures to downstream services.
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);

    private final CircuitBreakerRegistry registry;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerService() {
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
    }

    public void executeWithCircuitBreaker(String name, Runnable action) {
        CircuitBreaker breaker = breakers.computeIfAbsent(name,
                k -> registry.circuitBreaker(k));

        try {
            breaker.executeRunnable(action);
        } catch (Exception e) {
            log.error("Circuit breaker [{}] state={}: {}",
                    name, breaker.getState(), e.getMessage());
            throw e;
        }
    }

    public <T> T executeWithCircuitBreaker(String name, java.util.function.Supplier<T> supplier) {
        CircuitBreaker breaker = breakers.computeIfAbsent(name,
                k -> registry.circuitBreaker(k));

        try {
            return breaker.executeSupplier(supplier);
        } catch (Exception e) {
            log.error("Circuit breaker [{}] state={}: {}",
                    name, breaker.getState(), e.getMessage());
            throw e;
        }
    }
}
