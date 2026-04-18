package com.rate.ratelimiter.services.impl;

import com.rate.ratelimiter.entity.ApiClient;
import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.repository.ApiClientRepository;
import com.rate.ratelimiter.repository.ApiKeyRepository;
import com.rate.ratelimiter.services.ApiKeyService;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyServiceImpl implements ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final ApiClientRepository apiClientRepository;
    private final StringRedisTemplate redisTemplate;

    public ApiKeyServiceImpl(
        ApiKeyRepository apiKeyRepository,
        ApiClientRepository apiClientRepository,
        StringRedisTemplate redisTemplate
    ) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiClientRepository = apiClientRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    @Transactional
    public CreatedApiClient createClient(String name, String contactEmail) {
        String normalizedName = normalizeRequired(name, "name");
        String normalizedEmail = normalizeRequired(contactEmail, "contactEmail");

        if (apiClientRepository.findByName(normalizedName).isPresent()) {
            throw new IllegalStateException("Client name already exists: " + normalizedName);
        }
        if (apiClientRepository.findByContactEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new IllegalStateException("Client email already exists: " + normalizedEmail);
        }

        ApiClient client = new ApiClient();
        client.setName(normalizedName);
        client.setContactEmail(normalizedEmail);
        client.setActive(true);

        ApiClient saved = apiClientRepository.save(client);
        return new CreatedApiClient(
            saved.getId(),
            saved.getName(),
            saved.getContactEmail(),
            saved.isActive(),
            saved.getCreatedAt()
        );
    }

    @Override
    public Optional<ApiKey> findByRawKey(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isEmpty()) {
            return Optional.empty();
        }
        String keyHash = sha256(rawApiKey.trim());
        return apiKeyRepository.findWithClientByKeyHash(keyHash);
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
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return new ValidationResult(
                ValidationStatus.MISSING,
                null,
                null,
                "API key is missing"
            );
        }

        String normalized = rawApiKey.trim();
        if (normalized.length() < 16) {
            return new ValidationResult(
                ValidationStatus.MALFORMED,
                null,
                null,
                "API key format is invalid"
            );
        }

        Optional<ApiKey> keyOpt = findByRawKey(normalized);
        if (keyOpt.isEmpty()) {
            return new ValidationResult(
                ValidationStatus.NOT_FOUND,
                null,
                null,
                "API key not found"
            );
        }

        ApiKey apiKey = keyOpt.get();
        ApiClient client = apiKey.getClient();

        if (apiKey.isRevoked()) {
            return new ValidationResult(
                ValidationStatus.REVOKED,
                apiKey,
                client,
                "API key is revoked"
            );
        }

        Instant now = Instant.now();
        if (
            apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(now)
        ) {
            return new ValidationResult(
                ValidationStatus.EXPIRED,
                apiKey,
                client,
                "API key is expired"
            );
        }

        if (client == null || !client.isActive()) {
            return new ValidationResult(
                ValidationStatus.CLIENT_INACTIVE,
                apiKey,
                client,
                "Client is inactive"
            );
        }

        return new ValidationResult(
            ValidationStatus.VALID,
            apiKey,
            client,
            "API key is valid"
        );
    }

    @Override
    @Transactional
    public CreatedApiKey createKey(
        UUID clientId,
        int rateLimitPerMinute,
        Instant expiresAt
    ) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId is required");
        }
        if (rateLimitPerMinute <= 0) {
            throw new IllegalArgumentException(
                "rateLimitPerMinute must be > 0"
            );
        }

        ApiClient client = apiClientRepository.findById(clientId).orElseThrow(() ->
            new EntityNotFoundException("ApiClient not found: " + clientId)
        );

        if (!client.isActive()) {
            throw new IllegalStateException(
                "Cannot create key for inactive client: " + clientId
            );
        }

        String plaintextKey = generatePlaintextKey();
        String keyPrefix = plaintextKey.substring(0, 12);
        String keyHash = sha256(plaintextKey);

        ApiKey apiKey = new ApiKey();
        apiKey.setClient(client);
        apiKey.setKeyPrefix(keyPrefix);
        apiKey.setKeyHash(keyHash);
        apiKey.setRateLimitPerMinute(rateLimitPerMinute);
        apiKey.setRevoked(false);
        apiKey.setExpiresAt(expiresAt);

        ApiKey saved = apiKeyRepository.save(apiKey);

        return new CreatedApiKey(
            saved.getId(),
            client.getId(),
            saved.getKeyPrefix(),
            plaintextKey,
            saved.getRateLimitPerMinute(),
            saved.getExpiresAt(),
            saved.getCreatedAt()
        );
    }

    private String generatePlaintextKey() {
        String random =
            UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "");
        return "rk_live_" + random;
    }

    @Override
    public void revokeKey(UUID apiKeyId) {
        ApiKey key = apiKeyRepository.findById(apiKeyId).orElseThrow(() ->
            new EntityNotFoundException("ApiKey not found: " + apiKeyId)
        );
        key.setRevoked(true);
        apiKeyRepository.save(key);
    }

    @Override
    public ApiKey updateRateLimit(UUID apiKeyId, int newRateLimitPerMinute) {
        if (newRateLimitPerMinute <= 0) {
            throw new IllegalArgumentException("newRateLimitPerMinute must be > 0");
        }

        ApiKey key = apiKeyRepository.findById(apiKeyId).orElseThrow(() ->
            new EntityNotFoundException("ApiKey not found: " + apiKeyId)
        );
        key.setRateLimitPerMinute(newRateLimitPerMinute);
        ApiKey updated = apiKeyRepository.save(key);
        clearRateLimiterState(apiKeyId);
        return updated;
    }

    @Override
    public void touchLastUsed(UUID apiKeyId, Instant usedAt) {
        ApiKey key = apiKeyRepository.findById(apiKeyId).orElseThrow(() ->
            new EntityNotFoundException("ApiKey not found: " + apiKeyId)
        );
        key.setLastUsedAt(usedAt);
        apiKeyRepository.save(key);
    }

    @Override
    public Optional<ApiKey> findById(UUID apiKeyId) {
        return apiKeyRepository.findById(apiKeyId);
    }

    @Override
    public Optional<ApiClient> findClientById(UUID clientId) {
        return apiClientRepository.findById(clientId);
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private void clearRateLimiterState(UUID apiKeyId) {
        String pattern = "ratelimit:" + apiKeyId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
