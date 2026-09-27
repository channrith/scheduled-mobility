package com.mobility.core.identity.token;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mobility.core.identity.user.User;
import com.mobility.core.identity.user.UserRole;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues short-lived access tokens. Claims: {@code sub} (user id), {@code roles} (role names),
 * {@code corp} (corporate_id → roles held there) and {@code lang}.
 */
@Component
public class AccessTokenIssuer {

	public static final String ROLES_CLAIM = "roles";

	public static final String CORPORATE_CLAIM = "corp";

	public static final String LANGUAGE_CLAIM = "lang";

	private final JwtEncoder encoder;

	private final TokenProperties props;

	private final Clock clock;

	AccessTokenIssuer(JwtEncoder encoder, TokenProperties props, Clock clock) {
		this.encoder = encoder;
		this.props = props;
		this.clock = clock;
	}

	public record AccessToken(String value, Instant expiresAt) {
	}

	/** {@code user} must have its roles loaded. */
	public AccessToken issue(User user) {
		List<String> roles = user.getRoles().stream().map(r -> r.getRole().name()).distinct().sorted().toList();
		Map<String, List<String>> corporate = user.getRoles()
			.stream()
			.filter(r -> r.getScopeId() != null)
			.collect(Collectors.groupingBy(r -> r.getScopeId().toString(), TreeMap::new,
					Collectors.mapping((UserRole r) -> r.getRole().name(), Collectors.toList())));
		return issue(user.getId(), roles, corporate, user.getPreferredLang().code());
	}

	AccessToken issue(UUID userId, List<String> roles, Map<String, List<String>> corporate, String language) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(props.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(props.issuer())
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString())
			.claim(ROLES_CLAIM, roles)
			.claim(CORPORATE_CLAIM, corporate)
			.claim(LANGUAGE_CLAIM, language)
			.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
	}
}
