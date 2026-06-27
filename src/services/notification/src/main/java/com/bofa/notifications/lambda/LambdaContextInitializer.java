package com.bofa.notifications.lambda;

import com.bofa.notifications.NotificationApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

/**
 * Shared Spring ApplicationContext initializer for Lambda handlers.
 *
 * Creates a single Spring context at cold start (cached across invocations).
 * SnapStart captures this initialized context for near-zero cold start.
 *
 * Replaces the Spring Boot embedded server startup; Lambda handlers
 * pull beans from this context instead of relying on @Autowired.
 */
public final class LambdaContextInitializer {

    private static final Logger log = LoggerFactory.getLogger(LambdaContextInitializer.class);

    private static volatile ApplicationContext context;
    private static final Object LOCK = new Object();

    private LambdaContextInitializer() {}

    public static ApplicationContext getContext() {
        if (context == null) {
            synchronized (LOCK) {
                if (context == null) {
                    log.info("Initializing Spring context for Lambda (cold start)");
                    long start = System.currentTimeMillis();
                    context = SpringApplication.run(NotificationApplication.class,
                            "--spring.profiles.active=aws",
                            "--spring.main.web-application-type=none");
                    log.info("Spring context initialized in {}ms",
                            System.currentTimeMillis() - start);
                }
            }
        }
        return context;
    }
}
