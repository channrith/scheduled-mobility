package com.mobility.location;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("staging")
class StagingProfileTests {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        JwtTestSupport.register(registry);
    }

	@Test
	void stagingReadinessProbeIsAvailableWithoutAuthentication() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
	}
}
