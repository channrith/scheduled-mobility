package com.mobility.core.identity.otp;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param hmacSecret server-side secret used to hash OTP codes (a bare hash of a short numeric code is
 * trivially brute-forced)
 * @param fixedCode test environments without an SMS gateway only: every phone receives this code (see
 * {@link OtpCodes})
 */
@Validated
@ConfigurationProperties("identity.otp")
public record OtpProperties(
		@DefaultValue("5m") Duration ttl,
		@DefaultValue("60s") Duration resendCooldown,
		@DefaultValue("5") @Min(1) int maxRequestsPerHour,
		@DefaultValue("5") @Min(1) int maxVerifyAttempts,
		@DefaultValue("4") @Min(4) int codeLength,
		@Size(min = 32, message = "identity.otp.hmac-secret (OTP_HMAC_SECRET) must be at least 32 characters")
		String hmacSecret,
		String fixedCode) {
}
