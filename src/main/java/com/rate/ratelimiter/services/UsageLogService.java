package com.rate.ratelimiter.services;

import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.entity.UsageLog;
import java.time.Instant;

/**
 * Service for recording gateway request usage/audit events.
 *
 * <p>This service should be called after each proxied request (or rejected request)
 * to persist request metadata for analytics, debugging, and admin reporting.</p>
 */
public interface UsageLogService {
    /**
     * Persists a usage log entry.
     *
     * @param apiKey authenticated API key used for the request
     * @param requestPath incoming request path (e.g. /gateway/users/42)
     * @param method HTTP method (GET, POST, ...)
     * @param statusCode final status returned to caller
     * @param upstreamStatusCode status from upstream, if available (nullable)
     * @param latencyMs end-to-end request latency in milliseconds
     * @param clientIp caller IP address (nullable)
     * @param userAgent caller User-Agent header value (nullable)
     * @param requestedAt request timestamp (if null, implementation should use Instant.now())
     * @return persisted UsageLog entity
     */
    UsageLog logRequest(
        ApiKey apiKey,
        String requestPath,
        String method,
        int statusCode,
        Integer upstreamStatusCode,
        long latencyMs,
        String clientIp,
        String userAgent,
        Instant requestedAt
    );

    /**
     * Convenience overload that uses current system time.
     */
    default UsageLog logRequest(
        ApiKey apiKey,
        String requestPath,
        String method,
        int statusCode,
        Integer upstreamStatusCode,
        long latencyMs,
        String clientIp,
        String userAgent
    ) {
        return logRequest(
            apiKey,
            requestPath,
            method,
            statusCode,
            upstreamStatusCode,
            latencyMs,
            clientIp,
            userAgent,
            Instant.now()
        );
    }
}
