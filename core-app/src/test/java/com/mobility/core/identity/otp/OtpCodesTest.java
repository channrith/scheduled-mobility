package com.mobility.core.identity.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class OtpCodesTest {

	@Test
	void generatesRandomDigitCodesByDefault() {
		OtpCodes codes = new OtpCodes(props(null), new MockEnvironment());

		assertThat(codes.next()).matches("\\d{6}");
		assertThat(Stream.generate(codes::next).limit(20).distinct().count()).isGreaterThan(1);
	}

	@Test
	void usesTheFixedCodeWhenConfigured() {
		OtpCodes codes = new OtpCodes(props("123456"), new MockEnvironment());

		assertThat(codes.next()).isEqualTo("123456");
		assertThat(codes.next()).isEqualTo("123456");
	}

	@Test
	void blankFixedCodeMeansRandom() {
		OtpCodes codes = new OtpCodes(props(""), new MockEnvironment());

		assertThat(codes.next()).matches("\\d{6}");
	}

	@ParameterizedTest
	@ValueSource(strings = { "12345", "1234567", "12345a", " 123456" })
	void rejectsFixedCodesThatAreNotSixDigits(String fixed) {
		assertThatThrownBy(() -> new OtpCodes(props(fixed), new MockEnvironment()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("OTP_FIXED_CODE");
	}

	@ParameterizedTest
	@ValueSource(strings = { "prod", "production" })
	void refusesFixedCodeInProduction(String profile) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("staging", profile);

		assertThatThrownBy(() -> new OtpCodes(props("123456"), environment))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("never be set in production");
	}

	@Test
	void productionWithoutFixedCodeIsFine() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("prod");

		assertThat(new OtpCodes(props(null), environment).next()).matches("\\d{6}");
	}

	private static OtpProperties props(String fixedCode) {
		return new OtpProperties(Duration.ofMinutes(5), Duration.ofSeconds(60), 5, 5, 6,
				"test-otp-secret-0123456789abcdefghijkl", fixedCode);
	}
}
