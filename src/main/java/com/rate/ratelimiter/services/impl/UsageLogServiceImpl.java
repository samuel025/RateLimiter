package com.rate.ratelimiter.services.impl;

import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.entity.UsageLog;
import com.rate.ratelimiter.repository.UsageLogRepository;
import com.rate.ratelimiter.services.UsageLogService;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<UsageLogView> getById(UUID usageLogId) {
        if (usageLogId == null) {
            throw new IllegalArgumentException("usageLogId is required");
        }

        return usageLogRepository.findById(usageLogId).map(this::toView);
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public Page<UsageLogView> search(
        UUID apiKeyId,
        String method,
        Integer statusCode,
        Integer minStatusCode,
        Integer maxStatusCode,
        String requestPathContains,
        String clientIp,
        Instant requestedFrom,
        Instant requestedTo,
        Pageable pageable
    ) {
        if (pageable == null) {
            throw new IllegalArgumentException("pageable is required");
        }
        if (minStatusCode != null && maxStatusCode != null && minStatusCode > maxStatusCode) {
            throw new IllegalArgumentException("minStatusCode cannot be greater than maxStatusCode");
        }
        if (requestedFrom != null && requestedTo != null && requestedFrom.isAfter(requestedTo)) {
            throw new IllegalArgumentException("requestedFrom cannot be after requestedTo");
        }

        Specification<UsageLog> spec = (root, query, cb) -> cb.conjunction();

        if (apiKeyId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("apiKey").get("id"), apiKeyId));
        }
        if (!isBlank(method)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("method"), method.trim().toUpperCase()));
        }
        if (statusCode != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("statusCode"), statusCode));
        }
        if (minStatusCode != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("statusCode"), minStatusCode));
        }
        if (maxStatusCode != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("statusCode"), maxStatusCode));
        }
        if (!isBlank(requestPathContains)) {
            String like = "%" + requestPathContains.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("requestPath")), like));
        }
        if (!isBlank(clientIp)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("clientIp"), clientIp.trim()));
        }
        if (requestedFrom != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("requestedAt"), requestedFrom));
        }
        if (requestedTo != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("requestedAt"), requestedTo));
        }

        return usageLogRepository.findAll(spec, pageable).map(this::toView);
    }

    private UsageLogView toView(UsageLog log) {
        return new UsageLogView(
            log.getId(),
            log.getApiKey() != null ? log.getApiKey().getId() : null,
            log.getRequestPath(),
            log.getMethod(),
            log.getStatusCode(),
            log.getLatencyMs(),
            log.getUpstreamStatusCode(),
            log.getClientIp(),
            log.getUserAgent(),
            log.getRequestedAt()
        );
    }

    private boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
