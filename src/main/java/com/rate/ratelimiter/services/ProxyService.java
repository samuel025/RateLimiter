package com.rate.ratelimiter.services;

import com.rate.ratelimiter.entity.ApiKey;
import java.util.Map;

/**
 * Service that forwards authenticated gateway requests to the configured upstream API.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Build outbound URL from gateway path + query params</li>
 *   <li>Forward method, headers, and optional body</li>
 *   <li>Return upstream response details without controller-specific formatting</li>
 * </ul>
 */
public interface ProxyService {
    /**
     * Proxies a request to upstream and returns normalized response details.
     *
     * @param apiKey authenticated API key context for this request
     * @param method HTTP method (GET, POST, PUT, PATCH, DELETE, ...)
     * @param path path segment after gateway prefix (e.g. "/users/42")
     * @param queryParams request query params
     * @param headers incoming request headers to forward/filter
     * @param requestBody raw request body bytes (nullable for body-less methods)
     * @return proxy response containing status, headers, and body
     */
    ProxyResponse forward(
        ApiKey apiKey,
        String method,
        String path,
        Map<String, String[]> queryParams,
        Map<String, String> headers,
        byte[] requestBody
    );

    /**
     * Immutable response contract returned by proxy operations.
     *
     * @param statusCode upstream HTTP status code
     * @param headers response headers (single-value map)
     * @param body raw response bytes (nullable)
     */
    record ProxyResponse(
        int statusCode,
        Map<String, String> headers,
        byte[] body
    ) {}
}
