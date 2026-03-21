package com.rate.ratelimiter.config;

import com.rate.ratelimiter.interceptors.AuthInterceptor;
import com.rate.ratelimiter.interceptors.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/gateway/**")
            .excludePathPatterns("/actuator/health", "/error");

        // Must run AFTER auth so AUTH_API_KEY is available.
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/gateway/**")
            .excludePathPatterns("/actuator/health", "/error");
    }
}
