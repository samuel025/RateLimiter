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

    private final RestTemplate restTemplate;
    private final UsageLogService usageLogService;
    private final String upstreamBaseUrl;

    public ProxyServiceImpl(
        RestTemplate restTemplate,
        UsageLogService usageLogService,
        @Value("${gateway.upstream.base-url}") String upstreamBaseUrl
    ) {
        this.restTemplate = restTemplate;
        this.usageLogService = usageLogService;
        this.upstreamBaseUrl = upstreamBaseUrl;
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

            return new ProxyResponse(finalStatus, responseHeaders, responseBody);

        } catch (HttpStatusCodeException ex) {
            // Upstream returned non-2xx/3xx; still proxied response.
            upstreamStatus = ex.getStatusCode().value();
            finalStatus = upstreamStatus;
            responseBody = ex.getResponseBodyAsByteArray();
            responseHeaders = flattenHeaders(ex.getResponseHeaders());

            return new ProxyResponse(finalStatus, responseHeaders, responseBody);

        } catch (ResourceAccessException ex) {
            // Timeout / DNS / connection error.
            finalStatus = 502;
            responseBody = ("Upstream unavailable").getBytes(StandardCharsets.UTF_8);
            log.error("Upstream unavailable: {}", ex.getMessage(), ex);
            responseHeaders = Map.of("Content-Type", "text/plain; charset=UTF-8");

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
