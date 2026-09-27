package com.mobility.core;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Logs in through the real OTP endpoints. */
public class AuthTestClient {

	public record Tokens(String accessToken, String tokenType, long expiresIn, String refreshToken,
			long refreshExpiresIn) {
	}

	private final RestTestClient client;

	private final CapturingSmsSender sms;

	AuthTestClient(RestTestClient client, CapturingSmsSender sms) {
		this.client = client;
		this.sms = sms;
	}

	public Tokens login(String phone) {
		client.post()
			.uri("/api/v1/auth/otp/request")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("phone", phone))
			.exchange()
			.expectStatus()
			.isAccepted();
		return client.post()
			.uri("/api/v1/auth/otp/verify")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("phone", phone, "code", sms.lastCodeTo(phone)))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Tokens.class)
			.returnResult()
			.getResponseBody();
	}

	public RestTestClient.ResponseSpec refresh(String refreshToken) {
		return client.post()
			.uri("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("refreshToken", refreshToken))
			.exchange();
	}

	public Tokens refreshOk(String refreshToken) {
		return refresh(refreshToken).expectStatus().isOk().expectBody(Tokens.class).returnResult().getResponseBody();
	}
}
