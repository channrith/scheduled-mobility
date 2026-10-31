package com.mobility.core.identity.token;

import java.util.Map;

import com.mobility.platform.security.MobilityJwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Core-app's public signing key(s) as a JSON Web Key Set, so the gateway and other services verify access tokens
 * without storing a key. Tokens carry the key's {@code kid}; a rotation publishes old and new key side by side.
 * Only public parameters are served. Internal: the gateway does not route this path, and it is not in the API docs
 * (they cover {@code /api/**}).
 */
@RestController
class JwksController {

	private final Map<String, Object> jwks;

	JwksController(RSAKey jwtSigningKey) {
		// toPublicJWK() drops every private parameter (d, p, q, ...).
		this.jwks = new JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject();
	}

	@GetMapping(MobilityJwt.JWKS_PATH)
	ResponseEntity<Map<String, Object>> jwks() {
		// Services cache keys themselves; a short HTTP cache is only a hint.
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5))).body(jwks);
	}
}
