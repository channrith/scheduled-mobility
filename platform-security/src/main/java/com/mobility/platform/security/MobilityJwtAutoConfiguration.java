package com.mobility.platform.security;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Verifying core-app's tokens without wiring: a service on this module gets a decoder built from
 * {@code mobility.jwt.*} ({@link MobilityJwtProperties}) and the roles converter, so it only declares its route
 * rules. A service that defines its own decoder (core-app: it holds the signing key) keeps it. A service without
 * its own decoder and without {@code mobility.jwt.*} fails at startup, naming the settings.
 */
@AutoConfiguration
@EnableConfigurationProperties(MobilityJwtProperties.class)
public class MobilityJwtAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.SERVLET)
	static class Servlet {

		@Bean
		@ConditionalOnMissingBean
		JwtDecoder jwtDecoder(MobilityJwtProperties properties, ObjectProvider<Clock> clock) {
			return MobilityJwt.decoder(properties.keySource(), properties.issuer(), clock.getIfAvailable(Clock::systemUTC));
		}

		/** Spring Security's servlet resource server picks this bean up by type. */
		@Bean
		@ConditionalOnMissingBean
		JwtAuthenticationConverter jwtAuthenticationConverter() {
			return MobilityJwt.authenticationConverter();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.REACTIVE)
	@ConditionalOnClass(name = "reactor.core.publisher.Mono")
	static class Reactive {

		@Bean
		@ConditionalOnMissingBean
		ReactiveJwtDecoder reactiveJwtDecoder(MobilityJwtProperties properties, ObjectProvider<Clock> clock) {
			return ReactiveMobilityJwt.decoder(properties.keySource(), properties.issuer(), clock.getIfAvailable(Clock::systemUTC));
		}
	}
}
