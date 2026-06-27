package com.allpets.api.reviews;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Reviews wiring: enables {@code @Scheduled} (for the daily refresh in {@code ReviewsService}),
 * binds {@link ReviewsProperties}, and provides a timeout-bounded {@link RestClient} for the
 * Google Places API. Tight connect/read timeouts ensure a hung Google call can never stall
 * the scheduler thread.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReviewsProperties.class)
class ReviewsConfig {

    @Bean
    RestClient placesRestClient() {
        // Spring Boot 4 / Spring Framework 7 removed the boot ClientHttpRequestFactoryBuilder
        // + ClientHttpRequestFactorySettings abstraction, so wire spring-web's JDK-based
        // factory directly: connect timeout on the HttpClient, read timeout on the factory.
        // Tight timeouts ensure a hung Google call can never stall the scheduler thread.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl("https://places.googleapis.com/v1")
                .requestFactory(requestFactory)
                .build();
    }
}
