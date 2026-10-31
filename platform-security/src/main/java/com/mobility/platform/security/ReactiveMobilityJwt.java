package com.mobility.platform.security;

import java.time.Clock;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import reactor.core.publisher.Mono;

/** {@link MobilityJwt} for reactive (WebFlux) users such as the API gateway. Kept apart so servlet users never load Reactor. */
public final class ReactiveMobilityJwt {

	private ReactiveMobilityJwt() {
	}

	public static ReactiveJwtDecoder decoder(MobilityJwt.KeySource keys, String issuer, Clock clock) {
		NimbusReactiveJwtDecoder decoder = keys.jwksUri() != null ? NimbusReactiveJwtDecoder.withJwkSetUri(keys.jwksUri()).build()
				: NimbusReactiveJwtDecoder.withPublicKey(keys.publicKey()).build();
		decoder.setJwtValidator(MobilityJwt.validator(issuer, clock));
		return decoder;
	}

	public static Converter<Jwt, Mono<AbstractAuthenticationToken>> authenticationConverter() {
		return new ReactiveJwtAuthenticationConverterAdapter(MobilityJwt.authenticationConverter());
	}
}
