package com.rate.ratelimiter.services.impl;

import com.rate.ratelimiter.services.RateLimiterService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;


@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    private final ConcurrentHashMap<WindowKey, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public RateLimitDecision checkAndConsume(UUID apiKeyId, int limitPerMinute, Instant now) {
        if (apiKeyId == null) {
            throw new IllegalArgumentException("apiKeyId is required");
        }
        if (limitPerMinute <= 0) {
            throw new IllegalArgumentException("limitPerMinute must be > 0");
        }
        if (now == null) {
            now = Instant.now();
        }

        Instant windowStart = now.truncatedTo(ChronoUnit.MINUTES);
        Instant resetAt = windowStart.plus(1, ChronoUnit.MINUTES);
        WindowKey key = new WindowKey(apiKeyId, windowStart);

        AtomicInteger counter = counters.computeIfAbsent(key, k -> new AtomicInteger(0));
        int newCount = counter.incrementAndGet();

        if (newCount <= limitPerMinute) {
            int remaining = Math.max(0, limitPerMinute - newCount);
            return RateLimitDecision.allowed(limitPerMinute, remaining, resetAt);
        }

        long retryAfter = Math.max(1, ChronoUnit.SECONDS.between(now, resetAt));
        return RateLimitDecision.denied(limitPerMinute, resetAt, retryAfter);
    }


    private record WindowKey(UUID apiKeyId, Instant windowStart) {}
}
