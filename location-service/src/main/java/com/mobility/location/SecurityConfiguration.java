package com.mobility.location;

import java.time.Clock;

import com.mobility.platform.security.MobilityJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** Accepts core-app's access tokens, checked as every service checks them ({@link MobilityJwt}); only drivers may ping. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain security(HttpSecurity http) throws Exception {
		return http.csrf(c -> c.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info", "/error").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/locations").hasRole("DRIVER")
				.anyRequest().denyAll())
			.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(MobilityJwt.authenticationConverter())))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(LocationProperties properties) {
		LocationProperties.Jwt jwt = properties.jwt();
		return MobilityJwt.decoder(MobilityJwt.KeySource.of(jwt.jwksUri(), jwt.publicKey(), jwt.publicKeyFile(), "JWT_JWKS_URI"),
				jwt.issuer(), Clock.systemUTC());
	}
}
