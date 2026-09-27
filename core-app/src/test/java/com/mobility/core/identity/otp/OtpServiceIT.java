package com.mobility.core.identity.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mobility.core.CapturingSmsSender;
import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

@IntegrationTest
class OtpServiceIT {

	@Autowired
	OtpService otp;

	@Autowired
	CapturingSmsSender sms;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void storesOnlyAHashWithFiveMinuteExpiry() {
		String phone = SamplePhones.next();
		otp.request(phone, Language.KM);
		String code = sms.lastCodeTo(phone);

		String stored = redis.opsForValue().get(OtpService.codeKey(phone));
		assertThat(stored).isNotBlank().doesNotContain(code);
		assertThat(redis.getExpire(OtpService.codeKey(phone))).isBetween(290L, 300L);
	}

	@Test
	void correctCodeVerifiesExactlyOnce() {
		String phone = SamplePhones.next();
		otp.request(phone, Language.KM);
		String code = sms.lastCodeTo(phone);

		assertThatCode(() -> otp.verify(phone, code)).doesNotThrowAnyException();
		assertThatThrownBy(() -> otp.verify(phone, code)).satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "otp.invalid"));
	}

	@Test
	void codeIsBoundToItsPhoneNumber() {
		String phone = SamplePhones.next();
		String other = SamplePhones.next();
		otp.request(phone, Language.KM);
		otp.request(other, Language.KM);

		assertThatThrownBy(() -> otp.verify(other, sms.lastCodeTo(phone)))
			.satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "otp.invalid"));
	}

	@Test
	void fifthWrongAttemptBurnsTheCode() {
		String phone = SamplePhones.next();
		otp.request(phone, Language.KM);
		String code = sms.lastCodeTo(phone);
		String wrong = code.equals("000000") ? "111111" : "000000";

		for (int i = 1; i <= 4; i++) {
			assertThatThrownBy(() -> otp.verify(phone, wrong))
				.satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "otp.invalid"));
		}
		assertThatThrownBy(() -> otp.verify(phone, wrong))
			.satisfies(ex -> assertProblem(ex, HttpStatus.TOO_MANY_REQUESTS, "otp.attempts-exceeded"));
		// Even the right code no longer works.
		assertThatThrownBy(() -> otp.verify(phone, code))
			.satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "otp.invalid"));
	}

	@Test
	void newRequestReplacesPreviousCodeAndResetsAttempts() {
		String phone = SamplePhones.next();
		otp.request(phone, Language.KM);
		String first = sms.lastCodeTo(phone);
		redis.delete("identity:otp:cooldown:" + phone);
		otp.request(phone, Language.KM);
		String second = sms.lastCodeTo(phone);

		if (!first.equals(second)) {
			assertThatThrownBy(() -> otp.verify(phone, first)).isInstanceOf(ApiException.class);
		}
		assertThatCode(() -> otp.verify(phone, second)).doesNotThrowAnyException();
	}

	static void assertProblem(Throwable ex, HttpStatus status, String code) {
		assertThat(ex).isInstanceOf(ApiException.class);
		assertThat(((ApiException) ex).getStatus()).isEqualTo(status);
		assertThat(((ApiException) ex).getCode()).isEqualTo(code);
	}
}
