package com.rate.ratelimiter.controllers;

import com.rate.ratelimiter.entity.ApiKey;
import com.rate.ratelimiter.interceptors.AuthInterceptor;
import com.rate.ratelimiter.services.ProxyService;
import com.rate.ratelimiter.services.ProxyService.ProxyResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catch-all gateway controller that proxies authenticated/rate-limited traffic.
 */
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    private final ProxyService proxyService;

    public GatewayController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(
        HttpServletRequest request,
        @RequestBody(required = false) byte[] body
    ) throws IOException {

        Object attr = request.getAttribute(AuthInterceptor.ATTR_AUTH_API_KEY);
        if (!(attr instanceof ApiKey apiKey)) {
            return ResponseEntity.status(401).body("Missing auth context".getBytes());
        }

        String fullUri = request.getRequestURI();          // e.g. /gateway/users/42
        String contextPath = request.getContextPath();     // usually ""
        String base = contextPath + "/gateway";
        String proxiedPath = fullUri.startsWith(base) ? fullUri.substring(base.length()) : "";
        if (proxiedPath.isBlank()) {
            proxiedPath = "/";
        }

        Map<String, String[]> queryParams = request.getParameterMap();
        Map<String, String> headers = extractHeaders(request);

        byte[] requestBody = body;
        if (requestBody == null) {
            requestBody = StreamUtils.copyToByteArray(request.getInputStream());
        }

        ProxyResponse upstream = proxyService.forward(
            apiKey,
            request.getMethod(),
            proxiedPath,
            queryParams,
            headers,
            requestBody
        );

        HttpHeaders responseHeaders = new HttpHeaders();
        if (upstream.headers() != null) {
            upstream.headers().forEach(responseHeaders::add);
        }

        return ResponseEntity
            .status(upstream.statusCode())
            .headers(responseHeaders)
            .body(upstream.body());
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> map = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            map.put(name, request.getHeader(name));
        }
        return map;
    }
}
