package com.rate.ratelimiter.controllers;

import com.rate.ratelimiter.services.impl.ProxyServiceImpl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/admin/upstream")
public class UpstreamHealthController {

    private final RestTemplate restTemplate;
    private final ProxyServiceImpl proxyService;
    private final String upstreamBaseUrl;
    private final String healthPath;

    public UpstreamHealthController(
        RestTemplate restTemplate,
        ProxyServiceImpl proxyService,
        @Value("${gateway.upstream.base-url}") String upstreamBaseUrl,
        @Value("${gateway.upstream.health-path:/}") String healthPath
    ) {
        this.restTemplate = restTemplate;
        this.proxyService = proxyService;
        this.upstreamBaseUrl = upstreamBaseUrl;
        this.healthPath = healthPath;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        String url = normalizeUrl(upstreamBaseUrl, healthPath);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upstreamUrl", url);
        body.put("circuitState", proxyService.circuitStateName());
        body.put("lastFailureReason", proxyService.lastFailureReason());

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
            int status = response.getStatusCode().value();
            body.put("upstreamStatus", status);
            body.put("reachable", status >= 200 && status < 400);

            if (status >= 200 && status < 400) {
                return ResponseEntity.ok(body);
            }
            return ResponseEntity.status(503).body(body);
        } catch (RuntimeException ex) {
            body.put("reachable", false);
            body.put("error", ex.getClass().getSimpleName());
            body.put("message", ex.getMessage());
            return ResponseEntity.status(503).body(body);
        }
    }

    private String normalizeUrl(String baseUrl, String healthPath) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String path = healthPath == null ? "/" : healthPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return base + path;
    }
}
