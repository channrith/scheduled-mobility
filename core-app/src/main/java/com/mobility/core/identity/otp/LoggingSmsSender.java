package com.mobility.core.identity.otp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Development stub: writes SMS messages (including OTP codes) to the log instead of sending them. */
@Component
class LoggingSmsSender implements SmsSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

	LoggingSmsSender() {
		log.warn("LoggingSmsSender is active: OTP codes are written to the log. Do not use in production.");
	}

	@Override
	public void send(String phoneE164, String message) {
		log.info("SMS to {}: {}", PhoneNumbers.mask(phoneE164), message);
	}
}
