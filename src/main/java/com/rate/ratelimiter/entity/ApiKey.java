package com.rate.ratelimiter.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private ApiClient client;

    @Column(name = "key_hash", nullable = false, unique = true, length = 128)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "is_revoked", nullable = false)
    private boolean revoked = false;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(
        mappedBy = "apiKey",
        cascade = CascadeType.ALL,
        orphanRemoval = false,
        fetch = FetchType.LAZY
    )
    private List<UsageLog> usageLogs = new ArrayList<>();

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
