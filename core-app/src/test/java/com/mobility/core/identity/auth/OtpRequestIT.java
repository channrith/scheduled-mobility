package com.mobility.core.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.mobility.core.CapturingSmsSender;
import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class OtpRequestIT {

	@Autowired
	RestTestClient client;

	@Autowired
	CapturingSmsSender sms;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void sendsKhmerSmsByDefault() {
		String phone = SamplePhones.next();

		request(phone, null).expectStatus()
			.isAccepted()
			.expectBody()
			.jsonPath("$.expiresInSeconds").isEqualTo(300)
			.jsonPath("$.resendAfterSeconds").isEqualTo(60);

		assertThat(sms.lastTo(phone)).get().extracting(CapturingSmsSender.Sms::message).asString()
			.contains("លេខកូដផ្ទៀងផ្ទាត់").containsPattern("\\b\\d{4}\\b");
	}

	@Test
	void sendsEnglishSmsWhenRequested() {
		String phone = SamplePhones.next();

		request(phone, "en").expectStatus().isAccepted();

		assertThat(sms.lastTo(phone)).get().extracting(CapturingSmsSender.Sms::message).asString()
			.contains("is your verification code");
	}

	@Test
	void normalizesLocalCambodianNumbers() {
		request("097 123 4567", null).expectStatus().isAccepted();

		assertThat(sms.lastTo("+855971234567")).isPresent();
		redis.delete(redis.keys("identity:otp:*+855971234567"));
	}

	@Test
	void rejectsInvalidPhoneWithLocalizedProblem() {
		request("12345", "en").expectStatus()
			.isBadRequest()
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.code").isEqualTo("phone.invalid")
			.jsonPath("$.title").isEqualTo("Bad request")
			.jsonPath("$.detail").isEqualTo("The phone number is not valid.");

		request("12345", null).expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.detail").isEqualTo("លេខទូរស័ព្ទមិនត្រឹមត្រូវ។");
	}

	@Test
	void rejectsMissingPhoneAsValidationProblem() {
		client.post()
			.uri("/api/v1/auth/otp/request")
			.contentType(MediaType.APPLICATION_JSON)
			.header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.body(Map.of())
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.code").isEqualTo("validation")
			.jsonPath("$.errors[0].field").isEqualTo("phone")
			.jsonPath("$.errors[0].message").isEqualTo("must not be blank");
	}

	@Test
	void secondRequestWithinCooldownIsRateLimited() {
		String phone = SamplePhones.next();
		request(phone, null).expectStatus().isAccepted();

		request(phone, "en").expectStatus()
			.isEqualTo(429)
			.expectHeader().exists(HttpHeaders.RETRY_AFTER)
			.expectBody()
			.jsonPath("$.code").isEqualTo("otp.resend-cooldown");

		assertThat(sms.countTo(phone)).isEqualTo(1);
	}

	@Test
	void sixthRequestWithinAnHourIsRateLimited() {
		String phone = SamplePhones.next();
		for (int i = 0; i < 5; i++) {
			redis.delete("identity:otp:cooldown:" + phone);
			request(phone, null).expectStatus().isAccepted();
		}
		redis.delete("identity:otp:cooldown:" + phone);

		request(phone, null).expectStatus()
			.isEqualTo(429)
			.expectHeader().exists(HttpHeaders.RETRY_AFTER)
			.expectBody()
			.jsonPath("$.code").isEqualTo("otp.hourly-limit");

		assertThat(sms.countTo(phone)).isEqualTo(5);
	}

	private RestTestClient.ResponseSpec request(String phone, String language) {
		var spec = client.post().uri("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON);
		if (language != null) {
			spec = spec.header(HttpHeaders.ACCEPT_LANGUAGE, language);
		}
		return spec.body(Map.of("phone", phone)).exchange();
	}
}
