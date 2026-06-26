package com.allpets.api.reviews;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl("https://places.googleapis.com/v1")
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
