package com.mobility.core.identity.auth;

import io.swagger.v3.oas.annotations.Operation;
import com.mobility.core.shared.openapi.ProblemResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;

import com.mobility.core.identity.auth.AuthService.Tokens;
import com.mobility.core.identity.otp.OtpProperties;
import com.mobility.core.identity.token.TokenProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Phone OTP login and session tokens")
class AuthController {

	private final AuthService auth;

	private final OtpProperties otpProperties;

	private final TokenProperties tokenProperties;

	AuthController(AuthService auth, OtpProperties otpProperties, TokenProperties tokenProperties) {
		this.auth = auth;
		this.otpProperties = otpProperties;
		this.tokenProperties = tokenProperties;
	}

	/** Always 202 for a valid phone, whether or not an account exists (no account enumeration). */
	@Operation(summary = "Send a login code by SMS",
			description = "Accepts Cambodian local formats (`012 345 678`) or E.164. The code is valid for 5 minutes.")
	@ProblemResponse(status = 429, description = "`otp.resend-cooldown` (60 s between codes) or `otp.hourly-limit` "
			+ "(5 per hour); see `Retry-After`")
	@PostMapping("/otp/request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	OtpRequested requestOtp(@Valid @RequestBody OtpRequest request, Locale locale) {
		auth.requestOtp(request.phone(), locale);
		return new OtpRequested(otpProperties.ttl().toSeconds(), otpProperties.resendCooldown().toSeconds());
	}

	@Operation(summary = "Verify the code and start a session",
			description = "Unknown phone numbers are registered as passengers. Returns a 15-minute access token and a "
					+ "30-day refresh token.")
	@ProblemResponse(status = 400, description = "`otp.invalid` (wrong or expired code) or `phone.invalid`")
	@ProblemResponse(status = 403, description = "`account.inactive`: the account is suspended or disabled")
	@ProblemResponse(status = 429, description = "`otp.attempts-exceeded`: 5 wrong codes; request a new one")
	@PostMapping("/otp/verify")
	TokenResponse verifyOtp(@Valid @RequestBody OtpVerify request, Locale locale) {
		return toResponse(auth.verifyOtp(request.phone(), request.code(), locale));
	}

	@Operation(summary = "Rotate the refresh token",
			description = "Returns new tokens; the presented refresh token stops working. Presenting an already-rotated "
					+ "token revokes the whole session (theft detection), so always send an Idempotency-Key and retry "
					+ "with the same key.")
	@ProblemResponse(status = 401, description = "`refresh-token.invalid`: unknown, expired, rotated or revoked")
	@PostMapping("/refresh")
	TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return toResponse(auth.refresh(request.refreshToken()));
	}

	@Operation(summary = "End the session", description = "Revokes the refresh token's session. Unknown tokens are ignored.")
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@Valid @RequestBody RefreshRequest request) {
		auth.logout(request.refreshToken());
	}

	private TokenResponse toResponse(Tokens tokens) {
		return new TokenResponse(tokens.access().value(), "Bearer", tokenProperties.accessTokenTtl().toSeconds(),
				tokens.refresh().value(), tokenProperties.refreshTokenTtl().toSeconds());
	}

	record OtpRequest(@NotBlank String phone) {
	}

	record OtpRequested(long expiresInSeconds, long resendAfterSeconds) {
	}

	record OtpVerify(@NotBlank String phone, @NotBlank String code) {
	}

	record RefreshRequest(@NotBlank String refreshToken) {
	}

	record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
			long refreshExpiresIn) {
	}
}
