package com.mobility.gateway;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BackendDownTests {

	@DynamicPropertySource
	static void backends(DynamicPropertyRegistry registry) throws IOException {
		// A port that was free a moment ago: nothing listens there.
		int closed;
		try (ServerSocket socket = new ServerSocket(0)) {
			closed = socket.getLocalPort();
		}
		registry.add("CORE_APP_URL", () -> "http://127.0.0.1:" + closed);
	}

	@Autowired
	WebTestClient client;

	@Test
	void unreachableBackendIsServiceUnavailable() {
		// A public route: no token needed to reach the (missing) backend.
		client.post()
			.uri("/api/v1/auth/otp/request")
			.header("Accept-Language", "en")
			.exchange()
			.expectStatus()
			.isEqualTo(503)
			.expectBody()
			.jsonPath("$.code").isEqualTo("gateway.upstream-unavailable")
			.jsonPath("$.title").isEqualTo("Service unavailable");
	}
}
