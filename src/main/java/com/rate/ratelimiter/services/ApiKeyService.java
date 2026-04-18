package com.rate.ratelimiter.services;

import com.rate.ratelimiter.entity.ApiClient;
import com.rate.ratelimiter.entity.ApiKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyService {

    /**
     * Resolves an incoming raw API key string to a persisted ApiKey record.
     * Implementations should hash the raw key and look up by key hash.
     */
    Optional<ApiKey> findByRawKey(String rawApiKey);

    /**
     * Performs full key usability checks:
     * - key exists
     * - key not revoked
     * - key not expired
     * - owning client active
     */
    ValidationResult validateForRequest(String rawApiKey);

    /**
     * Creates a new API client.
     */
    CreatedApiClient createClient(String name, String contactEmail);

    /**
     * Creates a new API key for a client and returns the plaintext key one time.
     * Persist only hash/prefix in storage.
     */
    CreatedApiKey createKey(UUID clientId, int rateLimitPerMinute, Instant expiresAt);

    /**
     * Revokes an existing key by id.
     */
    void revokeKey(UUID apiKeyId);

    /**
     * Updates rate limit policy for a key.
     */
    ApiKey updateRateLimit(UUID apiKeyId, int newRateLimitPerMinute);

    /**
     * Marks key as used (e.g., update lastUsedAt).
     */
    void touchLastUsed(UUID apiKeyId, Instant usedAt);

    /**
     * Utility lookup for admin/read use-cases.
     */
    Optional<ApiKey> findById(UUID apiKeyId);

    /**
     * Utility lookup for client operations.
     */
    Optional<ApiClient> findClientById(UUID clientId);

    enum ValidationStatus {
        VALID,
        MISSING,
        MALFORMED,
        NOT_FOUND,
        REVOKED,
        EXPIRED,
        CLIENT_INACTIVE
    }

    record ValidationResult(
        ValidationStatus status,
        ApiKey apiKey,
        ApiClient client,
        String message
    ) {
        public boolean isValid() {
            return status == ValidationStatus.VALID;
        }
    }

    /**
     * Value returned once at creation time.
     * plaintextKey must never be persisted.
     */
    record CreatedApiKey(
        UUID apiKeyId,
        UUID clientId,
        String keyPrefix,
        String plaintextKey,
        int rateLimitPerMinute,
        Instant expiresAt,
        Instant createdAt
    ) {}

    record CreatedApiClient(
        UUID clientId,
        String name,
        String contactEmail,
        boolean active,
        Instant createdAt
    ) {}
}
