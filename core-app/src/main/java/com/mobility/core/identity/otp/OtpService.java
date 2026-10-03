package com.mobility.core.identity.otp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.web.ApiException;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Phone OTP codes, stored in Redis only as an HMAC with a TTL. Enforces a resend cooldown, an hourly
 * request cap and a per-code verify attempt limit, all per phone number.
 */
@Service
public class OtpService {

	private static final String PREFIX = "identity:otp:";

	private final StringRedisTemplate redis;

	private final SmsSender sms;

	private final MessageSource messages;

	private final OtpProperties props;

	private final OtpCodes codes;

	OtpService(StringRedisTemplate redis, SmsSender sms, MessageSource messages, OtpProperties props,
			OtpCodes codes) {
		this.redis = redis;
		this.sms = sms;
		this.messages = messages;
		this.props = props;
		this.codes = codes;
	}

	/** Generates and sends a new code, replacing any previous one for this phone. */
	public void request(String phoneE164, Language language) {
		enforceRequestLimits(phoneE164);

		String code = codes.next();
		redis.opsForValue().set(codeKey(phoneE164), hash(phoneE164, code), props.ttl());
		redis.delete(attemptsKey(phoneE164));

		String text = messages.getMessage("sms.otp", new Object[] { code, props.ttl().toMinutes() },
				language.locale());
		sms.send(phoneE164, text);
	}

	/**
	 * Consumes the code if it matches. Throws 400 for a wrong/expired code, and 429 once the attempt
	 * limit is reached (the code is then burned).
	 */
	public void verify(String phoneE164, String code) {
		String stored = redis.opsForValue().get(codeKey(phoneE164));
		if (stored == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "otp.invalid");
		}
		Long attempts = redis.opsForValue().increment(attemptsKey(phoneE164));
		if (attempts != null && attempts == 1) {
			redis.expire(attemptsKey(phoneE164), props.ttl());
		}

		boolean matches = code != null && MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
				hash(phoneE164, code).getBytes(StandardCharsets.US_ASCII));
		if (matches) {
			// Only one concurrent verify can win the delete; codes are strictly single-use.
			if (!Boolean.TRUE.equals(redis.delete(codeKey(phoneE164)))) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "otp.invalid");
			}
			redis.delete(attemptsKey(phoneE164));
			return;
		}
		if (attempts != null && attempts >= props.maxVerifyAttempts()) {
			redis.delete(java.util.List.of(codeKey(phoneE164), attemptsKey(phoneE164)));
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp.attempts-exceeded");
		}
		throw new ApiException(HttpStatus.BAD_REQUEST, "otp.invalid");
	}

	private void enforceRequestLimits(String phoneE164) {
		String cooldownKey = PREFIX + "cooldown:" + phoneE164;
		if (!props.resendCooldown().isZero()
				&& !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey, "1", props.resendCooldown()))) {
			long wait = secondsLeft(cooldownKey, props.resendCooldown());
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp.resend-cooldown", wait)
				.withHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
		}

		String hourlyKey = PREFIX + "hourly:" + phoneE164;
		redis.opsForValue().setIfAbsent(hourlyKey, "0", Duration.ofHours(1));
		Long count = redis.opsForValue().increment(hourlyKey);
		if (count != null && count > props.maxRequestsPerHour()) {
			long wait = secondsLeft(hourlyKey, Duration.ofHours(1));
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp.hourly-limit")
				.withHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
		}
	}

	private long secondsLeft(String key, Duration fallback) {
		Long ttl = redis.getExpire(key);
		return ttl != null && ttl > 0 ? ttl : fallback.toSeconds();
	}

	private String hash(String phoneE164, String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(props.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] digest = mac.doFinal((phoneE164 + ":" + code).getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(digest);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 unavailable", ex);
		}
	}

	static String codeKey(String phoneE164) {
		return PREFIX + "code:" + phoneE164;
	}

	private static String attemptsKey(String phoneE164) {
		return PREFIX + "attempts:" + phoneE164;
	}
}
