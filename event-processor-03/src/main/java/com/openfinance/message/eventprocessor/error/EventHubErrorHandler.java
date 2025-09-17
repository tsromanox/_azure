package com.openfinance.message.eventprocessor.error;

import com.azure.core.exception.*;
import com.azure.messaging.eventhubs.models.ErrorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventHubErrorHandler {

    private final MeterRegistry meterRegistry;

    // Error tracking
    private final ConcurrentHashMap<String, ErrorStatistics> errorStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastErrorTime = new ConcurrentHashMap<>();

    // Metrics
    private Counter transientErrorCounter;
    private Counter permanentErrorCounter;
    private Counter authenticationErrorCounter;
    private Counter throttlingErrorCounter;

    @jakarta.annotation.PostConstruct
    public void initializeMetrics() {
        this.transientErrorCounter = Counter.builder("eventhub.errors.transient")
                .description("Count of transient errors")
                .register(meterRegistry);

        this.permanentErrorCounter = Counter.builder("eventhub.errors.permanent")
                .description("Count of permanent errors")
                .register(meterRegistry);

        this.authenticationErrorCounter = Counter.builder("eventhub.errors.authentication")
                .description("Count of authentication errors")
                .register(meterRegistry);

        this.throttlingErrorCounter = Counter.builder("eventhub.errors.throttling")
                .description("Count of throttling errors")
                .register(meterRegistry);
    }

    public ErrorCategory handleError(ErrorContext errorContext) {
        Throwable throwable = errorContext.getThrowable();
        String partitionId = errorContext.getPartitionContext().getPartitionId();

        ErrorCategory category = categorizeError(throwable);
        updateErrorStatistics(partitionId, category);
        updateMetrics(category);

        switch (category) {
            case TRANSIENT:
                handleTransientError(errorContext, throwable);
                break;
            case AUTHENTICATION:
                handleAuthenticationError(errorContext, throwable);
                break;
            case THROTTLING:
                handleThrottlingError(errorContext, throwable);
                break;
            case PERMANENT:
                handlePermanentError(errorContext, throwable);
                break;
            default:
                handleUnknownError(errorContext, throwable);
        }

        return category;
    }

    public ErrorCategory categorizeError(Throwable throwable) {
        if (throwable == null) {
            return ErrorCategory.UNKNOWN;
        }

        // Check Azure Core exceptions
        if (throwable instanceof AzureException) {
            return categorizeAzureException((AzureException) throwable);
        }

        // Check by exception class name and message
        String className = throwable.getClass().getName();
        String message = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";

        // Authentication errors
        if (isAuthenticationError(className, message)) {
            return ErrorCategory.AUTHENTICATION;
        }

        // Throttling errors
        if (isThrottlingError(className, message)) {
            return ErrorCategory.THROTTLING;
        }

        // Transient errors
        if (isTransientError(className, message)) {
            return ErrorCategory.TRANSIENT;
        }

        // Network errors (usually transient)
        if (isNetworkError(className, message)) {
            return ErrorCategory.TRANSIENT;
        }

        // Default to permanent for unknown errors
        return ErrorCategory.PERMANENT;
    }

    private ErrorCategory categorizeAzureException(AzureException exception) {
        if (exception instanceof ServiceResponseException) {
            ServiceResponseException sre = (ServiceResponseException) exception;
            int statusCode = sre.getResponse() != null ?
                    sre.getResponse().getStatusCode() : 0;

            if (statusCode == 401 || statusCode == 403) {
                return ErrorCategory.AUTHENTICATION;
            } else if (statusCode == 429) {
                return ErrorCategory.THROTTLING;
            } else if (statusCode >= 500 && statusCode < 600) {
                return ErrorCategory.TRANSIENT;
            } else if (statusCode >= 400 && statusCode < 500) {
                return ErrorCategory.PERMANENT;
            }
        }

        if (exception instanceof ResourceNotFoundException) {
            return ErrorCategory.PERMANENT;
        }

        if (exception instanceof ResourceModifiedException) {
            return ErrorCategory.TRANSIENT;
        }

        if (exception instanceof TooManyRedirectsException) {
            return ErrorCategory.TRANSIENT;
        }

        return ErrorCategory.UNKNOWN;
    }

    private boolean isAuthenticationError(String className, String message) {
        return className.contains("UnauthorizedException") ||
                className.contains("AuthenticationException") ||
                className.contains("AuthorizationException") ||
                message.contains("401") ||
                message.contains("403") ||
                message.contains("unauthorized") ||
                message.contains("forbidden") ||
                message.contains("authentication") ||
                message.contains("authorization") ||
                message.contains("access denied");
    }

    private boolean isThrottlingError(String className, String message) {
        return className.contains("ThrottlingException") ||
                className.contains("RateLimitException") ||
                message.contains("429") ||
                message.contains("quota") ||
                message.contains("throttl") ||
                message.contains("rate limit") ||
                message.contains("too many requests");
    }

    private boolean isTransientError(String className, String message) {
        return className.contains("TimeoutException") ||
                className.contains("RetryableException") ||
                message.contains("timeout") ||
                message.contains("temporarily unavailable") ||
                message.contains("retry") ||
                message.contains("503") ||
                message.contains("service unavailable");
    }

    private boolean isNetworkError(String className, String message) {
        return className.contains("IOException") ||
                className.contains("SocketException") ||
                className.contains("ConnectException") ||
                className.contains("UnknownHostException") ||
                message.contains("connection") ||
                message.contains("socket") ||
                message.contains("network");
    }

    private void handleTransientError(ErrorContext context, Throwable throwable) {
        String partitionId = context.getPartitionContext().getPartitionId();
        log.warn("Transient error in partition {}: {}. Will retry automatically.",
                partitionId, throwable.getMessage());

        // Check if errors are happening too frequently
        ErrorStatistics stats = errorStats.get(partitionId);
        if (stats != null && stats.getRecentErrorRate() > 0.5) {
            log.warn("High error rate detected for partition {}: {}%",
                    partitionId, stats.getRecentErrorRate() * 100);
        }
    }

    private void handleAuthenticationError(ErrorContext context, Throwable throwable) {
        log.error("Authentication error in partition {}: {}",
                context.getPartitionContext().getPartitionId(), throwable.getMessage());

        // Could trigger re-authentication or send alert
        // For now, just log the error
    }

    private void handleThrottlingError(ErrorContext context, Throwable throwable) {
        String partitionId = context.getPartitionContext().getPartitionId();
        log.warn("Throttling error in partition {}: {}. Implementing backoff.",
                partitionId, throwable.getMessage());

        // Record last throttling time for backoff calculation
        lastErrorTime.put("throttle_" + partitionId, Instant.now());
    }

    private void handlePermanentError(ErrorContext context, Throwable throwable) {
        log.error("Permanent error in partition {}: {}",
                context.getPartitionContext().getPartitionId(),
                throwable.getMessage(), throwable);

        // These errors typically require manual intervention
    }

    private void handleUnknownError(ErrorContext context, Throwable throwable) {
        log.error("Unknown error in partition {}: {}",
                context.getPartitionContext().getPartitionId(),
                throwable.getMessage(), throwable);
    }

    private void updateErrorStatistics(String partitionId, ErrorCategory category) {
        errorStats.compute(partitionId, (k, v) -> {
            if (v == null) {
                v = new ErrorStatistics(partitionId);
            }
            v.recordError(category);
            return v;
        });
    }

    private void updateMetrics(ErrorCategory category) {
        switch (category) {
            case TRANSIENT:
                transientErrorCounter.increment();
                break;
            case AUTHENTICATION:
                authenticationErrorCounter.increment();
                break;
            case THROTTLING:
                throttlingErrorCounter.increment();
                break;
            case PERMANENT:
                permanentErrorCounter.increment();
                break;
        }
    }

    public Duration getBackoffDuration(String partitionId, int attemptNumber) {
        // Exponential backoff with jitter
        long baseDelayMs = 1000; // 1 second
        long maxDelayMs = 60000; // 60 seconds

        // Check if we're being throttled
        Instant lastThrottle = lastErrorTime.get("throttle_" + partitionId);
        if (lastThrottle != null &&
                Duration.between(lastThrottle, Instant.now()).getSeconds() < 30) {
            // If recently throttled, use longer backoff
            baseDelayMs = 5000;
        }

        long delayMs = Math.min(baseDelayMs * (1L << attemptNumber), maxDelayMs);

        // Add jitter (±20%)
        double jitter = 0.8 + (Math.random() * 0.4);
        delayMs = (long) (delayMs * jitter);

        return Duration.ofMillis(delayMs);
    }

    // Enum for error categories
    public enum ErrorCategory {
        TRANSIENT("Transient error that should be retried"),
        AUTHENTICATION("Authentication or authorization error"),
        THROTTLING("Rate limit or quota exceeded"),
        PERMANENT("Permanent error that should not be retried"),
        UNKNOWN("Unknown error type");

        private final String description;

        ErrorCategory(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    // Error statistics tracking
    private static class ErrorStatistics {
        private final String partitionId;
        private final AtomicLong totalErrors = new AtomicLong(0);
        private final AtomicLong recentErrors = new AtomicLong(0);
        private final AtomicLong[] errorsByCategory = new AtomicLong[ErrorCategory.values().length];
        private volatile Instant lastResetTime = Instant.now();

        public ErrorStatistics(String partitionId) {
            this.partitionId = partitionId;
            for (int i = 0; i < errorsByCategory.length; i++) {
                errorsByCategory[i] = new AtomicLong(0);
            }
        }

        public void recordError(ErrorCategory category) {
            totalErrors.incrementAndGet();
            recentErrors.incrementAndGet();
            errorsByCategory[category.ordinal()].incrementAndGet();

            // Reset recent errors every 5 minutes
            if (Duration.between(lastResetTime, Instant.now()).toMinutes() >= 5) {
                recentErrors.set(0);
                lastResetTime = Instant.now();
            }
        }

        public double getRecentErrorRate() {
            // Simple error rate calculation
            // In production, use a sliding window or decay function
            long recent = recentErrors.get();
            if (recent == 0) return 0.0;

            Duration timeSinceReset = Duration.between(lastResetTime, Instant.now());
            if (timeSinceReset.getSeconds() == 0) return 0.0;

            // Errors per second
            return (double) recent / timeSinceReset.getSeconds();
        }

        public long getErrorCount(ErrorCategory category) {
            return errorsByCategory[category.ordinal()].get();
        }

        public long getTotalErrors() {
            return totalErrors.get();
        }
    }
}
