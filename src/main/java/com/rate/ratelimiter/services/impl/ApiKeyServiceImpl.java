package com.rate.ratelimiter.services.impl;

import com.rate.ratelimiter.entity.ApiClient;
import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.repository.ApiClientRepository;
import com.rate.ratelimiter.repository.ApiKeyRepository;
import com.rate.ratelimiter.services.ApiKeyService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyServiceImpl implements ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final ApiClientRepository apiClientRepository;

    public ApiKeyServiceImpl(ApiKeyRepository apiKeyRepository, ApiClientRepository apiClientRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiClientRepository = apiClientRepository;
    }

    @Override
    public Optional<ApiKey> findByRawKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isEmpty()) {
            return Optional.empty();
        }
        String keyHash = sha256(rawApiKey.trim());
        return apiKeyRepository.findByKeyHash(keyHash);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    @Override
    public ValidationResult validateForRequest(String rawApiKey) {
        return null;
    }

    @Override
    public CreatedApiKey createKey(UUID clientId, int rateLimitPerMinute, Instant expiresAt) {
        return null;
    }

    @Override
    public void revokeKey(UUID apiKeyId) {
        
    }

    @Override
    public ApiKey updateRateLimit(UUID apiKeyId, int newRateLimitPerMinute) {
        return null;
    }

    @Override
    public void touchLastUsed(UUID apiKeyId, Instant usedAt) {
        
    }

    @Override
    public Optional<ApiKey> findById(UUID apiKeyId) {
        return null;
    }

    @Override
    public Optional<ApiClient> findClientById(UUID clientId) {
        return null;
    }
}
