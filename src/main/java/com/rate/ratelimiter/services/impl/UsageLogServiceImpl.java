package com.rate.ratelimiter.services.impl;

import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.entity.UsageLog;
import com.rate.ratelimiter.repository.UsageLogRepository;
import com.rate.ratelimiter.services.UsageLogService;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class UsageLogServiceImpl implements UsageLogService {

    private final UsageLogRepository usageLogRepository;

    public UsageLogServiceImpl(UsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    @Override
    @Transactional
    public UsageLog logRequest(
        ApiKey apiKey,
        String requestPath,
        String method,
        int statusCode,
        Integer upstreamStatusCode,
        long latencyMs,
        String clientIp,
        String userAgent,
        Instant requestedAt
    ) {
        if (apiKey == null) {
            throw new IllegalArgumentException("apiKey is required");
        }
        if (requestPath == null || requestPath.isBlank()) {
            throw new IllegalArgumentException("requestPath is required");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs cannot be negative");
        }

        UsageLog log = new UsageLog();
        log.setApiKey(apiKey);
        log.setRequestPath(requestPath.trim());
        log.setMethod(method.trim().toUpperCase());
        log.setStatusCode(statusCode);
        log.setUpstreamStatusCode(upstreamStatusCode);
        log.setLatencyMs(latencyMs);
        log.setClientIp(isBlank(clientIp) ? null : clientIp.trim());
        log.setUserAgent(isBlank(userAgent) ? null : userAgent.trim());

        if (requestedAt != null) {
            log.setRequestedAt(requestedAt);
        }

        return usageLogRepository.save(log);
    }

    private boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
