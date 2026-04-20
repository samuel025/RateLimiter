package com.rate.ratelimiter.controllers;

import com.rate.ratelimiter.services.ApiKeyService;
import com.rate.ratelimiter.services.ApiKeyService.CreatedApiClient;
import com.rate.ratelimiter.services.ApiKeyService.CreatedApiKey;
import com.rate.ratelimiter.services.UsageLogService;
import com.rate.ratelimiter.services.UsageLogService.UsageLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nonnull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
@Tag(name = "Admin", description = "Admin operations for clients and API keys")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final UsageLogService usageLogService;

    public AdminController(ApiKeyService apiKeyService, UsageLogService usageLogService) {
        this.apiKeyService = apiKeyService;
        this.usageLogService = usageLogService;
    }

    /**
     * Create a new API client.
     */
    @PostMapping("/clients")
    @Operation(summary = "Create API client")
    public ResponseEntity<CreateClientResponse> createClient(
        @RequestBody CreateClientRequest request
    ) {
        CreatedApiClient created = apiKeyService.createClient(
            request.name(),
            request.contactEmail()
        );

        CreateClientResponse response = new CreateClientResponse(
            created.clientId(),
            created.name(),
            created.contactEmail(),
            created.active(),
            created.createdAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Create a new API key for an existing client.
     */
    @PostMapping("/keys")
     @Operation(summary = "Create API key for existing client")
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
    @Operation(summary = "Revoke API key")
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
    @Operation(summary = "Update API key per-minute limit")
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
    @Operation(summary = "Get API key metadata")
    public ResponseEntity<?> getKey(@PathVariable UUID apiKeyId) {
        return apiKeyService
            .findById(apiKeyId)
            .<ResponseEntity<?>>map(k -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", k.getId());
                body.put("clientId", k.getClient().getId());
                body.put("keyPrefix", k.getKeyPrefix());
                body.put("rateLimitPerMinute", k.getRateLimitPerMinute());
                body.put("revoked", k.isRevoked());
                body.put("expiresAt", k.getExpiresAt());
                body.put("lastUsedAt", k.getLastUsedAt());
                body.put("createdAt", k.getCreatedAt());
                return ResponseEntity.ok(body);
            })
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
     * Search and view usage logs.
     */
    @GetMapping("/usage-logs")
    @Operation(summary = "Search usage logs")
    public ResponseEntity<Page<UsageLogView>> searchUsageLogs(
        @RequestParam(value = "apiKeyId", required = false) UUID apiKeyId,
        @RequestParam(value = "method", required = false) String method,
        @RequestParam(value = "statusCode", required = false) Integer statusCode,
        @RequestParam(value = "minStatusCode", required = false) Integer minStatusCode,
        @RequestParam(value = "maxStatusCode", required = false) Integer maxStatusCode,
        @RequestParam(value = "requestPathContains", required = false) String requestPathContains,
        @RequestParam(value = "clientIp", required = false) String clientIp,
        @RequestParam(value = "requestedFrom", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant requestedFrom,
        @RequestParam(value = "requestedTo", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant requestedTo,
        @PageableDefault(sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<UsageLogView> page = usageLogService.search(
            apiKeyId,
            method,
            statusCode,
            minStatusCode,
            maxStatusCode,
            requestPathContains,
            clientIp,
            requestedFrom,
            requestedTo,
            pageable
        );
        return ResponseEntity.ok(page);
    }

    /**
     * Fetch one usage log by id.
     */
    @GetMapping("/usage-logs/{usageLogId}")
    @Operation(summary = "Get usage log by id")
    public ResponseEntity<?> getUsageLogById(@PathVariable UUID usageLogId) {
        return usageLogService
            .getById(usageLogId)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of(
                        "status",
                        404,
                        "error",
                        "Not Found",
                        "message",
                        "Usage log not found",
                        "path",
                        "/admin/usage-logs/" + usageLogId,
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

    public record CreateClientRequest(
        @Nonnull String name,
        @Nonnull String contactEmail
    ) {}

    public record CreateClientResponse(
        UUID clientId,
        String name,
        String contactEmail,
        boolean active,
        Instant createdAt
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
