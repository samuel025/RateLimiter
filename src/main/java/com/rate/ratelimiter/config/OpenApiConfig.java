package com.rate.ratelimiter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(
                new Info()
                    .title("RateLimiter Gateway API")
                    .version("v1")
                    .description(
                        "API gateway with API key auth, per-key rate limiting, upstream proxying, and admin key management."
                    )
                    .contact(new Contact().name("RateLimiter Team"))
            )
            .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
            .schemaRequirement(
                API_KEY_SCHEME,
                new SecurityScheme()
                    .name("X-API-Key")
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .description("Send the issued API key in the X-API-Key header")
            );
    }
}
