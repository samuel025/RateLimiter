package com.rate.ratelimiter.interceptors;

import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.services.RateLimiterService;
import com.rate.ratelimiter.services.RateLimiterService.RateLimitDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces per-key rate limits for authenticated requests.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimiterService rateLimiterService, ObjectMapper objectMapper) {
        this.rateLimiterService = rateLimiterService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {

        Object attr = request.getAttribute(AuthInterceptor.ATTR_AUTH_API_KEY);
        if (!(attr instanceof ApiKey apiKey)) {
            writeUnauthorized(response, request.getRequestURI(), "Authentication context missing");
            return false;
        }

        RateLimitDecision decision = rateLimiterService.checkAndConsume(
            apiKey.getId(),
            apiKey.getRateLimitPerMinute(),
            Instant.now()
        );

        // Add rate-limit headers on every response path.
        setRateLimitHeaders(response, decision);

        if (!decision.allowed()) {
            writeTooManyRequests(response, request.getRequestURI(), decision);
            return false;
        }

        return true;
    }

    private void setRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetAt().getEpochSecond()));

        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String path, String message) throws IOException {
        int status = 401;
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", "Unauthorized");
        body.put("code", "UNAUTHORIZED");
        body.put("message", message);
        body.put("path", path);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void writeTooManyRequests(
        HttpServletResponse response,
        String path,
        RateLimitDecision decision
    ) throws IOException {
        int status = 429;
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", "Too Many Requests");
        body.put("code", "RATE_LIMIT_EXCEEDED");
        body.put("message", "Rate limit exceeded. Retry after " + decision.retryAfterSeconds() + " seconds.");
        body.put("path", path);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("limit", decision.limit());
        details.put("remaining", decision.remaining());
        details.put("resetAt", decision.resetAt().toString());
        details.put("retryAfterSeconds", decision.retryAfterSeconds());

        body.put("details", details);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
