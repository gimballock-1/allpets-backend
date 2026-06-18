package com.allpets.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the Actuator surface the k8s probes and the shared Prometheus scrape depend
 * on — exercised over a real servlet container (RANDOM_PORT), exactly how kubelet and
 * Prometheus reach it, rather than via MockMvc.
 *
 * <p>{@code @AutoConfigureObservability} re-enables metrics export, which {@code @SpringBootTest}
 * disables by default — without it the {@code /actuator/prometheus} scrape endpoint is
 * absent from the test context (it is present at runtime).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class ActuatorEndpointsTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void aggregateHealthIsUp() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/health", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessProbeIsUp() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/health/liveness", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessProbeIsUp() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void infoExposesBuildTraceability() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/info", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Pin the info.env contributor shape, not just loose substrings.
        assertThat(r.getBody()).contains("\"git\":{\"sha\":").contains("\"build\":{\"time\":");
    }

    @Test
    void prometheusEndpointIsScrapeable() {
        ResponseEntity<String> r = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("jvm_");
    }
}
