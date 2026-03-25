package com.rate.ratelimiter.controllers;

import com.rate.ratelimiter.services.ApiKeyService;
import com.rate.ratelimiter.services.ApiKeyService.CreatedApiKey;
import jakarta.annotation.Nonnull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin endpoints for API key lifecycle operations.
 *
 * <p>Protect these routes with admin auth in production (JWT, Basic, mTLS, etc.).</p>
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ApiKeyService apiKeyService;

    public AdminController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * Create a new API key for an existing client.
     */
    @PostMapping("/keys")
    public ResponseEntity<CreateKeyResponse> createKey(
       @RequestBody CreateKeyRequest request
    ) {
        CreatedApiKey created = apiKeyService.createKey(
            request.clientId(),
            request.rateLimitPerMinute(),
            request.expiresAt()
        );

        CreateKeyResponse response = new CreateKeyResponse(
            created.apiKeyId(),
            created.clientId(),
            created.keyPrefix(),
            created.plaintextKey(),
            created.rateLimitPerMinute(),
            created.expiresAt(),
            created.createdAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Revoke an API key by id.
     */
    @PostMapping("/keys/{apiKeyId}/revoke")
    public ResponseEntity<Map<String, Object>> revokeKey(
        @PathVariable UUID apiKeyId
    ) {
        apiKeyService.revokeKey(apiKeyId);
        return ResponseEntity.ok(
            Map.of(
                "status",
                "revoked",
                "apiKeyId",
                apiKeyId,
                "timestamp",
                Instant.now().toString()
            )
        );
    }

    /**
     * Update the per-minute rate limit for a key.
     */
    @PatchMapping("/keys/{apiKeyId}/rate-limit")
    public ResponseEntity<Map<String, Object>> updateRateLimit(
        @PathVariable UUID apiKeyId,
        @RequestParam("value") int value
    ) {
        var updated = apiKeyService.updateRateLimit(apiKeyId, value);
        return ResponseEntity.ok(
            Map.of(
                "apiKeyId",
                updated.getId(),
                "rateLimitPerMinute",
                updated.getRateLimitPerMinute(),
                "timestamp",
                Instant.now().toString()
            )
        );
    }

    /**
     * Optional quick read endpoint to verify a key exists.
     */
    @GetMapping("/keys/{apiKeyId}")
    public ResponseEntity<?> getKey(@PathVariable UUID apiKeyId) {
        return apiKeyService
            .findById(apiKeyId)
            .<ResponseEntity<?>>map(k ->
                ResponseEntity.ok(
                    Map.of(
                        "id",
                        k.getId(),
                        "clientId",
                        k.getClient().getId(),
                        "keyPrefix",
                        k.getKeyPrefix(),
                        "rateLimitPerMinute",
                        k.getRateLimitPerMinute(),
                        "revoked",
                        k.isRevoked(),
                        "expiresAt",
                        k.getExpiresAt(),
                        "lastUsedAt",
                        k.getLastUsedAt(),
                        "createdAt",
                        k.getCreatedAt()
                    )
                )
            )
            .orElseGet(() ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of(
                        "status",
                        404,
                        "error",
                        "Not Found",
                        "message",
                        "API key not found",
                        "path",
                        "/admin/keys/" + apiKeyId,
                        "timestamp",
                        Instant.now().toString()
                    )
                )
            );
    }

    /**
     * Request payload for create key endpoint.
     */
    public record CreateKeyRequest(
        @Nonnull UUID clientId,
        int rateLimitPerMinute,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant expiresAt
    ) {}

    /**
     * Response payload for create key endpoint.
     *
     * <p>plaintextKey is shown once at creation time.</p>
     */
    public record CreateKeyResponse(
        UUID apiKeyId,
        UUID clientId,
        String keyPrefix,
        String plaintextKey,
        int rateLimitPerMinute,
        Instant expiresAt,
        Instant createdAt
    ) {}
}
