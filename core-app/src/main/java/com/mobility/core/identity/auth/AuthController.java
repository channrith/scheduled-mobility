package com.mobility.core.identity.auth;

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
	@PostMapping("/otp/request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	OtpRequested requestOtp(@Valid @RequestBody OtpRequest request, Locale locale) {
		auth.requestOtp(request.phone(), locale);
		return new OtpRequested(otpProperties.ttl().toSeconds(), otpProperties.resendCooldown().toSeconds());
	}

	@PostMapping("/otp/verify")
	TokenResponse verifyOtp(@Valid @RequestBody OtpVerify request, Locale locale) {
		return toResponse(auth.verifyOtp(request.phone(), request.code(), locale));
	}

	@PostMapping("/refresh")
	TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return toResponse(auth.refresh(request.refreshToken()));
	}

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
