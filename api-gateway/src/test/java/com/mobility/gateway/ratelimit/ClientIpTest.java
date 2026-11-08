package com.mobility.gateway.ratelimit;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

	/** The pattern in application.yml (private ranges and loopback). */
	final ClientIp clientIp = new ClientIp(
			"(127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|10\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|192\\.168\\..*)");

	static MockServerHttpRequest from(String remote, String forwardedFor) {
		MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/auth/otp/request")
			.remoteAddress(new InetSocketAddress(remote, 40000));
		if (forwardedFor != null) {
			request.header("X-Forwarded-For", forwardedFor);
		}
		return request.build();
	}

	@Test
	void behindCaddyTheFirstForwardedAddressIsTheClient() {
		assertThat(clientIp.of(from("172.18.0.5", "203.0.113.7"))).isEqualTo("203.0.113.7");
		assertThat(clientIp.of(from("172.18.0.6", "203.0.113.7, 172.18.0.5"))).isEqualTo("203.0.113.7");
		assertThat(clientIp.of(from("127.0.0.1", "2001:db8::1"))).isEqualTo("2001:db8::1");
	}

	@Test
	void fromAnUntrustedCallerTheHeaderIsIgnored() {
		assertThat(clientIp.of(from("198.51.100.9", "203.0.113.7"))).isEqualTo("198.51.100.9");
	}

	@Test
	void withoutAUsableHeaderTheRemoteAddressCounts() {
		assertThat(clientIp.of(from("172.18.0.5", null))).isEqualTo("172.18.0.5");
		assertThat(clientIp.of(from("172.18.0.5", "unknown"))).isEqualTo("172.18.0.5");
		assertThat(clientIp.of(from("172.18.0.5", "rl:*"))).isEqualTo("172.18.0.5");
	}
}
