package com.mobility.core.identity.otp;

import java.security.SecureRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Produces OTP codes: random, or a fixed code for test environments that have no SMS gateway. A fixed
 * code makes any phone number's account reachable by anyone, so it is refused under a production profile.
 */
@Component
class OtpCodes {

	private static final Logger log = LoggerFactory.getLogger(OtpCodes.class);

	private final SecureRandom random = new SecureRandom();

	private final int length;

	private final String fixedCode;

	OtpCodes(OtpProperties props, Environment environment) {
		this.length = props.codeLength();
		this.fixedCode = StringUtils.hasText(props.fixedCode()) ? props.fixedCode() : null;
		if (fixedCode != null) {
			if (!fixedCode.matches("\\d{" + length + "}")) {
				throw new IllegalStateException(
						"identity.otp.fixed-code (OTP_FIXED_CODE) must be exactly " + length + " digits");
			}
			if (environment.matchesProfiles("prod | production")) {
				throw new IllegalStateException(
						"identity.otp.fixed-code (OTP_FIXED_CODE) must never be set in production");
			}
			log.warn("OTP_FIXED_CODE is set: every phone number logs in with the same code. "
					+ "Test environments only, never with real personal data.");
		}
	}

	String next() {
		if (fixedCode != null) {
			return fixedCode;
		}
		StringBuilder code = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			code.append(random.nextInt(10));
		}
		return code.toString();
	}
}
