package com.allpets.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.allpets.api.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Verifies the Actuator surface the k8s probes and the shared Prometheus scrape depend
 * on — exercised over a real servlet container (RANDOM_PORT), exactly how kubelet and
 * Prometheus reach it, rather than via MockMvc.
 *
 * <p>{@code management.defaults.metrics.export.enabled=true} re-enables metrics export,
 * which Spring Boot's test support disables by default — without it the
 * {@code /actuator/prometheus} scrape endpoint serves no metrics in the test context
 * (it is fully populated at runtime). This replaces the removed
 * {@code @AutoConfigureObservability} from Spring Boot 3.x.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.defaults.metrics.export.enabled=true")
class ActuatorEndpointsTest extends PostgresIntegrationTest {

    @LocalServerPort
    private int port;

    private RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @Test
    void aggregateHealthIsUp() {
        ResponseEntity<String> r = rest.get().uri("/actuator/health").retrieve().toEntity(String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessProbeIsUp() {
        ResponseEntity<String> r = rest.get().uri("/actuator/health/liveness").retrieve().toEntity(String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessProbeIsUp() {
        ResponseEntity<String> r = rest.get().uri("/actuator/health/readiness").retrieve().toEntity(String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void infoExposesBuildTraceability() {
        ResponseEntity<String> r = rest.get().uri("/actuator/info").retrieve().toEntity(String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Pin the info.env contributor shape, not just loose substrings.
        assertThat(r.getBody()).contains("\"git\":{\"sha\":").contains("\"build\":{\"time\":");
    }

    @Test
    void prometheusEndpointIsScrapeable() {
        ResponseEntity<String> r = rest.get().uri("/actuator/prometheus").retrieve().toEntity(String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("jvm_");
    }
}
