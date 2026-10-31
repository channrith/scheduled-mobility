package com.mobility.gateway.auth;

import com.mobility.gateway.error.EdgeProblems;
import com.mobility.platform.security.ReactiveMobilityJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

/**
 * The gateway checks credentials once for every service (phase 2, variant B): a missing, expired, forged or
 * wrong-issuer token gets 401 here and never reaches a backend. Valid tokens pass through unchanged and the services
 * verify them again. Only validity is checked: who may do what stays in the services ({@code @PreAuthorize}).
 * The 401 matches core-app's (code {@code auth.unauthorized}, {@code WWW-Authenticate: Bearer}), so apps and the
 * console's BFF (which refreshes on any 401) see one format.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	/** Reachable without a token. Everything else under {@code /api/} and {@code /ws/} needs one. */
	static final String[] PUBLIC = { "/api/v1/auth/**", "/actuator/health", "/actuator/health/**", "/actuator/info",
			"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**" };

	@Bean
	SecurityWebFilterChain security(ServerHttpSecurity http, ServerAuthenticationEntryPoint unauthorized,
			ServerAccessDeniedHandler forbidden) {
		return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
			.logout(ServerHttpSecurity.LogoutSpec::disable)
			.authorizeExchange(exchange -> exchange.pathMatchers(PUBLIC).permitAll()
				// Signed, short-lived document links: the token in the URL is the authorization (core-app checks it).
				.pathMatchers(HttpMethod.GET, "/api/v1/document-previews/*").permitAll()
				.pathMatchers("/api/**", "/ws/**").authenticated()
				// Unrouted paths: the gateway's own 404, token or not.
				.anyExchange().permitAll())
			.oauth2ResourceServer(rs -> rs
				.jwt(jwt -> jwt.jwtAuthenticationConverter(ReactiveMobilityJwt.authenticationConverter()))
				.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler(forbidden))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
			.build();
	}

	@Bean
	ServerAuthenticationEntryPoint problemAuthenticationEntryPoint(EdgeProblems problems) {
		return (exchange, ex) -> {
			exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			return problems.write(exchange, HttpStatus.UNAUTHORIZED, "auth.unauthorized");
		};
	}

	@Bean
	ServerAccessDeniedHandler problemAccessDeniedHandler(EdgeProblems problems) {
		return (exchange, ex) -> problems.write(exchange, HttpStatus.FORBIDDEN, "auth.forbidden");
	}
}
