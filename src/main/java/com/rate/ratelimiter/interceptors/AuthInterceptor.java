package com.rate.ratelimiter.interceptors;

import com.rate.ratelimiter.entity.ApiClient;
import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.services.ApiKeyService;
import com.rate.ratelimiter.services.ApiKeyService.ValidationResult;
import com.rate.ratelimiter.services.ApiKeyService.ValidationStatus;
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
 * AuthInterceptor validates incoming X-API-Key and stores auth context on request.
 *
 * <p>Request attributes set on success:</p>
 * <ul>
 *   <li>AUTH_API_KEY (ApiKey)</li>
 *   <li>AUTH_CLIENT (ApiClient)</li>
 * </ul>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String ATTR_AUTH_API_KEY = "AUTH_API_KEY";
    public static final String ATTR_AUTH_CLIENT = "AUTH_CLIENT";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {

        String rawApiKey = request.getHeader(API_KEY_HEADER);
        ValidationResult result = apiKeyService.validateForRequest(rawApiKey);

        if (!result.isValid()) {
            writeAuthError(response, result.status(), result.message());
            return false;
        }

        ApiKey apiKey = result.apiKey();
        ApiClient client = result.client();

        request.setAttribute(ATTR_AUTH_API_KEY, apiKey);
        request.setAttribute(ATTR_AUTH_CLIENT, client);

        // optional: mark usage timestamp for active key
        apiKeyService.touchLastUsed(apiKey.getId(), Instant.now());

        return true;
    }

    private void writeAuthError(HttpServletResponse response, ValidationStatus status, String message)
        throws IOException {

        int httpStatus = mapStatus(status);

        response.setStatus(httpStatus);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", httpStatus);
        body.put("error", httpErrorText(httpStatus));
        body.put("code", status.name());
        body.put("message", message); 
        body.put("path", null);

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private int mapStatus(ValidationStatus status) {
        return switch (status) {
            case MISSING, MALFORMED, NOT_FOUND -> 401;
            case REVOKED, EXPIRED, CLIENT_INACTIVE -> 403;
            case VALID -> 200; // not used for failures
        };
    }

    private String httpErrorText(int status) {
        return switch (status) {
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            default -> "Error";
        };
    }
}
