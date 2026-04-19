package com.rate.ratelimiter.services.impl;

import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.services.ProxyService;
import com.rate.ratelimiter.services.UsageLogService;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class ProxyServiceImpl implements ProxyService {

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final RestTemplate restTemplate;
    private final UsageLogService usageLogService;
    private final String upstreamBaseUrl;
    private final int idempotentMaxAttempts;
    private final long retryBackoffMillis;
    private final int failureThreshold;
    private final long circuitOpenSeconds;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile Instant openedAt = Instant.EPOCH;
    private volatile String lastFailureReason = "";

    public ProxyServiceImpl(
        RestTemplate restTemplate,
        UsageLogService usageLogService,
        @Value("${gateway.upstream.base-url}") String upstreamBaseUrl,
        @Value("${gateway.proxy.retry.max-attempts-idempotent:3}") int idempotentMaxAttempts,
        @Value("${gateway.proxy.retry.backoff-millis:150}") long retryBackoffMillis,
        @Value("${gateway.proxy.circuit-breaker.failure-threshold:5}") int failureThreshold,
        @Value("${gateway.proxy.circuit-breaker.open-seconds:30}") long circuitOpenSeconds
    ) {
        this.restTemplate = restTemplate;
        this.usageLogService = usageLogService;
        this.upstreamBaseUrl = upstreamBaseUrl;
        this.idempotentMaxAttempts = Math.max(1, idempotentMaxAttempts);
        this.retryBackoffMillis = Math.max(0, retryBackoffMillis);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.circuitOpenSeconds = Math.max(1, circuitOpenSeconds);
    }

    @Override
    public ProxyResponse forward(
        ApiKey apiKey,
        String method,
        String path,
        Map<String, String[]> queryParams,
        Map<String, String> headers,
        byte[] requestBody
    ) {
        if (apiKey == null) {
            throw new IllegalArgumentException("apiKey is required");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }

        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();

        String normalizedPath = normalizePath(path);
        String url = buildUrl(normalizedPath, queryParams);
        HttpMethod httpMethod = HttpMethod.valueOf(method.trim().toUpperCase());
        HttpHeaders outboundHeaders = buildOutboundHeaders(headers);
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(requestBody, outboundHeaders);

        Integer upstreamStatus = null;
        int finalStatus = 500;
        byte[] responseBody = null;
        Map<String, String> responseHeaders = Map.of();

        if (!allowRequestThroughCircuit()) {
            finalStatus = 503;
            responseBody = fallbackBody("Circuit breaker is open; upstream temporarily blocked.");
            responseHeaders = fallbackHeaders();
            return new ProxyResponse(finalStatus, responseHeaders, responseBody);
        }

        int maxAttempts = isIdempotent(httpMethod) ? idempotentMaxAttempts : 1;

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt += 1) {
                try {
                    ResponseEntity<byte[]> upstream = restTemplate.exchange(
                        URI.create(url),
                        httpMethod,
                        requestEntity,
                        byte[].class
                    );

                    upstreamStatus = upstream.getStatusCode().value();
                    finalStatus = upstreamStatus;
                    responseBody = upstream.getBody();
                    responseHeaders = flattenHeaders(upstream.getHeaders());

                    recordSuccess();
                    return new ProxyResponse(finalStatus, responseHeaders, responseBody);

                } catch (HttpStatusCodeException ex) {
                    upstreamStatus = ex.getStatusCode().value();

                    if (shouldRetryStatus(upstreamStatus) && attempt < maxAttempts) {
                        pauseBeforeRetry(attempt);
                        continue;
                    }

                    finalStatus = upstreamStatus;
                    responseBody = ex.getResponseBodyAsByteArray();
                    responseHeaders = flattenHeaders(ex.getResponseHeaders());

                    if (upstreamStatus >= 500) {
                        recordFailure("upstream-status-" + upstreamStatus);
                    } else {
                        recordSuccess();
                    }

                    return new ProxyResponse(finalStatus, responseHeaders, responseBody);

                } catch (ResourceAccessException ex) {
                    if (attempt < maxAttempts) {
                        pauseBeforeRetry(attempt);
                        continue;
                    }

                    finalStatus = 502;
                    responseBody = fallbackBody("Upstream unavailable after retries.");
                    responseHeaders = fallbackHeaders();
                    recordFailure("resource-access:" + ex.getClass().getSimpleName());
                    log.error("Upstream unavailable after retries: {}", ex.getMessage(), ex);

                    return new ProxyResponse(finalStatus, responseHeaders, responseBody);

                } catch (RuntimeException ex) {
                    if (attempt < maxAttempts && isIdempotent(httpMethod)) {
                        pauseBeforeRetry(attempt);
                        continue;
                    }

                    finalStatus = 502;
                    responseBody = fallbackBody("Gateway fallback response due to upstream instability.");
                    responseHeaders = fallbackHeaders();
                    recordFailure("runtime:" + ex.getClass().getSimpleName());
                    log.error("Gateway fallback due to runtime upstream exception", ex);

                    return new ProxyResponse(finalStatus, responseHeaders, responseBody);
                }
            }

            finalStatus = 502;
            responseBody = fallbackBody("Gateway fallback: retry budget exhausted.");
            responseHeaders = fallbackHeaders();
            recordFailure("retry-exhausted");
            return new ProxyResponse(finalStatus, responseHeaders, responseBody);
        } finally {
            long latencyMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
            String clientIp = extractClientIp(headers);
            String userAgent = firstHeader(headers, "User-Agent");

            usageLogService.logRequest(
                apiKey,
                normalizedPath.isBlank() ? "/" : normalizedPath,
                httpMethod.name(),
                finalStatus,
                upstreamStatus,
                latencyMs,
                clientIp,
                userAgent,
                startedAt
            );
        }
    }

    public boolean isCircuitOpen() {
        return circuitState == CircuitState.OPEN;
    }

    public String circuitStateName() {
        return circuitState.name();
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    private boolean allowRequestThroughCircuit() {
        if (circuitState == CircuitState.CLOSED || circuitState == CircuitState.HALF_OPEN) {
            return true;
        }

        if (openedAt.plusSeconds(circuitOpenSeconds).isAfter(Instant.now())) {
            return false;
        }

        synchronized (this) {
            if (circuitState == CircuitState.OPEN && openedAt.plusSeconds(circuitOpenSeconds).isBefore(Instant.now())) {
                circuitState = CircuitState.HALF_OPEN;
                return true;
            }
        }
        return circuitState != CircuitState.OPEN;
    }

    private boolean isIdempotent(HttpMethod method) {
        return HttpMethod.GET.equals(method)
            || HttpMethod.HEAD.equals(method)
            || HttpMethod.OPTIONS.equals(method)
            || HttpMethod.TRACE.equals(method)
            || HttpMethod.PUT.equals(method)
            || HttpMethod.DELETE.equals(method);
    }

    private boolean shouldRetryStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private void pauseBeforeRetry(int attempt) {
        if (retryBackoffMillis <= 0) {
            return;
        }

        try {
            Thread.sleep(retryBackoffMillis * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        circuitState = CircuitState.CLOSED;
        lastFailureReason = "";
    }

    private void recordFailure(String reason) {
        lastFailureReason = reason;
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            circuitState = CircuitState.OPEN;
            openedAt = Instant.now();
        }
    }

    private byte[] fallbackBody(String message) {
        return message.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> fallbackHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=UTF-8");
        headers.put("X-Gateway-Fallback", "true");
        headers.put("X-Circuit-State", circuitState.name());
        return headers;
    }
    
    private String extractClientIp(Map<String, String> headers) {
        String xff = firstHeader(headers, "X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return null;
        }
        int comma = xff.indexOf(',');
        return (comma > 0 ? xff.substring(0, comma) : xff).trim();
    }

    private String firstHeader(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty() || name == null) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    private String buildUrl(String path, Map<String, String[]> queryParams) {
        String base = stripTrailingSlash(upstreamBaseUrl);
        StringBuilder url = new StringBuilder(base).append(path);

        String query = buildQueryString(queryParams);
        if (!query.isBlank()) {
            url.append("?").append(query);
        }
        return url.toString();
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("gateway.upstream.base-url is not configured");
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String buildQueryString(Map<String, String[]> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();

            if (values == null || values.length == 0) {
                parts.add(urlEncode(key));
                continue;
            }
            for (String value : values) {
                StringJoiner sj = new StringJoiner("=");
                sj.add(urlEncode(key));
                sj.add(urlEncode(value == null ? "" : value));
                parts.add(sj.toString());
            }
        }
        return String.join("&", parts);
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private HttpHeaders buildOutboundHeaders(Map<String, String> headers) {
        HttpHeaders outbound = new HttpHeaders();
        if (headers == null || headers.isEmpty()) {
            return outbound;
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null) {
                continue;
            }

            String lower = name.toLowerCase();
            if (lower.equals("host")
                || lower.equals("content-length")
                || lower.equals("connection")
                || lower.equals("transfer-encoding")
                || lower.equals("x-api-key")) {
                continue;
            }

            outbound.add(name, value);
        }
        return outbound;
    }

    private Map<String, String> flattenHeaders(HttpHeaders httpHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        if (httpHeaders == null || httpHeaders.isEmpty()) {
            return result;
        }

        httpHeaders.forEach((name, values) -> {
            if (values == null || values.isEmpty()) return;
            result.put(name, values.get(0));
        });
        return result;
    }
}
