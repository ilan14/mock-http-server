package com.lan.mock.monitoring;

import com.lan.mock.MockApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MockApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorTest {
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void healthAndJvmMetricsAreAvailableOnApplicationPort() throws Exception {
        var health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("UP");
        for (String metric : new String[]{"jvm.memory.used", "jvm.threads.live", "jvm.classes.loaded"}) {
            var response = get("/actuator/metrics/" + metric);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = mapper.readTree(response.body());
            assertThat(body.path("name").asText()).isEqualTo(metric);
            assertThat(body.path("measurements").size()).isGreaterThan(0);
        }
    }

    @Test
    void prometheusExportsJvmAndGcMetricsWithApplicationLabel() throws Exception {
        var response = get("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/plain");
        assertThat(response.body()).contains("jvm_memory_used_bytes", "jvm_threads_live_threads",
                "jvm_classes_loaded_classes", "jvm_gc_memory_allocated_bytes_total",
                "application=\"mock-http-server\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
