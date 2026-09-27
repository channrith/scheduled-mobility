package com.mobility.core.identity.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.mobility.core.shared.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque, rotating refresh tokens grouped in families (one family per login). Presenting an already
 * rotated token is treated as theft: the whole family is revoked.
 */
@Service
public class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private final RefreshTokenRepository tokens;

	private final TokenProperties props;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	RefreshTokenService(RefreshTokenRepository tokens, TokenProperties props, Clock clock) {
		this.tokens = tokens;
		this.props = props;
		this.clock = clock;
	}

	public record IssuedRefreshToken(String value, Instant expiresAt) {
	}

	public record Rotation(UUID userId, UUID familyId, IssuedRefreshToken next) {
	}

	/** Starts a new token family (a new login session). */
	@Transactional
	public IssuedRefreshToken issueNewFamily(UUID userId) {
		return create(userId, UUID.randomUUID(), clock.instant()).issued();
	}

	/**
	 * Revokes the presented token and issues its successor. Revocations are committed even when this
	 * method rejects the token (hence {@code noRollbackFor}).
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public Rotation rotate(String rawToken) {
		Instant now = clock.instant();
		RefreshToken current = tokens.findByTokenHashForUpdate(hash(rawToken)).orElseThrow(RefreshTokenService::invalid);
		if (current.isRevoked()) {
			int revoked = tokens.revokeFamily(current.getFamilyId(), now);
			log.warn("Refresh token reuse detected for user {} (family {}); revoked {} active token(s)",
					current.getUserId(), current.getFamilyId(), revoked);
			throw invalid();
		}
		if (current.isExpired(now)) {
			throw invalid();
		}
		Created next = create(current.getUserId(), current.getFamilyId(), now);
		current.revoke(now, next.id());
		return new Rotation(current.getUserId(), current.getFamilyId(), next.issued());
	}

	@Transactional
	public void revokeFamily(UUID familyId) {
		tokens.revokeFamily(familyId, clock.instant());
	}

	/** Logout: revokes the session the token belongs to. Unknown tokens are ignored. */
	@Transactional
	public void revokeFamilyOf(String rawToken) {
		tokens.findByTokenHashForUpdate(hash(rawToken)).ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), clock.instant()));
	}

	/** Signs the user out everywhere, e.g. on suspension. */
	@Transactional
	public void revokeAllForUser(UUID userId) {
		tokens.revokeAllForUser(userId, clock.instant());
	}

	private record Created(UUID id, IssuedRefreshToken issued) {
	}

	private Created create(UUID userId, UUID familyId, Instant now) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = now.plus(props.refreshTokenTtl());
		RefreshToken token = tokens.save(new RefreshToken(userId, familyId, hash(raw), expiresAt));
		return new Created(token.getId(), new IssuedRefreshToken(raw, expiresAt));
	}

	/** Plain SHA-256 is sufficient: the token carries 256 bits of entropy. */
	static byte[] hash(String rawToken) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.US_ASCII));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "refresh-token.invalid");
	}
}
