package com.rate.ratelimiter.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "usage_logs")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UsageLog {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    @Column(name = "request_path", nullable = false, length = 512)
    private String requestPath;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "upstream_status_code")
    private Integer upstreamStatusCode;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @PrePersist
    void onCreate() {
        this.requestedAt = Instant.now();
    }
}
