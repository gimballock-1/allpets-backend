package com.allpets.api.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ContactRateLimitInterceptor} on the {@code /contact} path (14.2).
 * {@code GET /reviews} is deliberately NOT limited: it serves read-only cached data.
 */
@Configuration
class RateLimitWebConfig implements WebMvcConfigurer {

    private final ContactRateLimitInterceptor contactRateLimitInterceptor;

    RateLimitWebConfig(ContactRateLimitInterceptor contactRateLimitInterceptor) {
        this.contactRateLimitInterceptor = contactRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(contactRateLimitInterceptor).addPathPatterns("/contact");
    }
}
