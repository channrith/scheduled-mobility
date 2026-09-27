package com.mobility.core.identity.auth;

import java.util.Locale;

import com.mobility.core.identity.otp.OtpService;
import com.mobility.core.identity.otp.PhoneNumbers;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.AccessTokenIssuer.AccessToken;
import com.mobility.core.identity.token.RefreshTokenService;
import com.mobility.core.identity.token.RefreshTokenService.IssuedRefreshToken;
import com.mobility.core.identity.token.RefreshTokenService.Rotation;
import com.mobility.core.identity.user.User;
import com.mobility.core.identity.user.UserRepository;
import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {

	private final OtpService otp;

	private final UserRepository users;

	private final AccessTokenIssuer accessTokens;

	private final RefreshTokenService refreshTokens;

	AuthService(OtpService otp, UserRepository users, AccessTokenIssuer accessTokens,
			RefreshTokenService refreshTokens) {
		this.otp = otp;
		this.users = users;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
	}

	record Tokens(AccessToken access, IssuedRefreshToken refresh) {
	}

	/** Sends an OTP in the user's preferred language, or the request language for unknown phones. */
	@Transactional(readOnly = true)
	void requestOtp(String rawPhone, Locale requestLocale) {
		String phone = normalize(rawPhone);
		Language language = users.findByPhoneE164(phone)
			.map(User::getPreferredLang)
			.orElse(Language.fromLocale(requestLocale));
		otp.request(phone, language);
	}

	/** Verifies the OTP and starts a session. Unknown phones self-register as passengers. */
	@Transactional
	Tokens verifyOtp(String rawPhone, String code, Locale requestLocale) {
		String phone = normalize(rawPhone);
		otp.verify(phone, code);
		User user = users.findByPhoneE164(phone)
			.orElseGet(() -> users.save(User.registerPassenger(phone, Language.fromLocale(requestLocale))));
		requireActive(user);
		return new Tokens(accessTokens.issue(user), refreshTokens.issueNewFamily(user.getId()));
	}

	@Transactional(noRollbackFor = ApiException.class)
	Tokens refresh(String rawRefreshToken) {
		Rotation rotation = refreshTokens.rotate(rawRefreshToken);
		User user = users.findWithRolesById(rotation.userId()).orElseThrow();
		if (!user.isActive()) {
			refreshTokens.revokeFamily(rotation.familyId());
			throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh-token.invalid");
		}
		return new Tokens(accessTokens.issue(user), rotation.next());
	}

	@Transactional
	void logout(String rawRefreshToken) {
		refreshTokens.revokeFamilyOf(rawRefreshToken);
	}

	private static void requireActive(User user) {
		if (!user.isActive()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "account.inactive");
		}
	}

	static String normalize(String rawPhone) {
		return PhoneNumbers.normalize(rawPhone)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "phone.invalid"));
	}
}
