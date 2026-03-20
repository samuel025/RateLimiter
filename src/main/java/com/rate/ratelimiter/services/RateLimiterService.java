package com.rate.ratelimiter.services;

import java.time.Instant;
import java.util.UUID;

/**
 * Service responsible for enforcing per-API-key request rate limits.
 *
 * <p>This service should be called after API key authentication succeeds.
 * It performs an atomic "check + consume" operation for the current request.</p>
 */
public interface RateLimiterService {

    /**
     * Checks whether a request is allowed for the given API key and consumes one
     * request unit if allowed.
     *
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>If current usage is below the key's limit, allow and increment usage.</li>
     *   <li>If current usage reached/exceeded the limit, deny without consuming extra quota.</li>
     *   <li>Return metadata useful for headers and 429 responses.</li>
     * </ul>
     *
     * @param apiKeyId unique identifier of the API key being evaluated
     * @param limitPerMinute allowed requests per minute for this API key
     * @param now timestamp used for window calculation (injectable for testability)
     * @return decision containing allow/deny and quota metadata
     */
    RateLimitDecision checkAndConsume(UUID apiKeyId, int limitPerMinute, Instant now);

    /**
     * Convenience overload that uses the current system time.
     *
     * @param apiKeyId unique identifier of the API key being evaluated
     * @param limitPerMinute allowed requests per minute for this API key
     * @return decision containing allow/deny and quota metadata
     */
    default RateLimitDecision checkAndConsume(UUID apiKeyId, int limitPerMinute) {
        return checkAndConsume(apiKeyId, limitPerMinute, Instant.now());
    }

    /**
     * Immutable result object representing the outcome of a rate-limit check.
     *
     * @param allowed true if the request can proceed; false if it must be rejected
     * @param limit configured request limit for the current window
     * @param remaining remaining requests available in the current window
     * @param resetAt instant when the current window resets
     * @param retryAfterSeconds number of seconds until next allowed request when denied
     */
    record RateLimitDecision(
        boolean allowed,
        int limit,
        int remaining,
        Instant resetAt,
        long retryAfterSeconds
    ) {
        /**
         * Factory for an allowed decision.
         */
        public static RateLimitDecision allowed(int limit, int remaining, Instant resetAt) {
            return new RateLimitDecision(true, limit, remaining, resetAt, 0);
        }

        /**
         * Factory for a denied decision.
         */
        public static RateLimitDecision denied(int limit, Instant resetAt, long retryAfterSeconds) {
            return new RateLimitDecision(false, limit, 0, resetAt, retryAfterSeconds);
        }
    }
}
