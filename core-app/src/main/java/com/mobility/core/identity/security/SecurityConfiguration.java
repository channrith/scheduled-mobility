package com.mobility.core.identity.security;

import com.mobility.core.shared.web.Problems;
import com.mobility.platform.security.MobilityJwt;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Stateless JWT security for the whole API. 401/403 are rendered as problem+json. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, AuthenticationEntryPoint entryPoint,
			AccessDeniedHandler accessDeniedHandler) throws Exception {
		http.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/v1/auth/**").permitAll()
				// Public keys for the other services (internal network; the gateway does not route it).
				.requestMatchers(HttpMethod.GET, MobilityJwt.JWKS_PATH).permitAll()
				// Signed, short-lived document links: the token in the URL is the authorization.
				.requestMatchers(HttpMethod.GET, "/api/v1/document-previews/*").permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
				.requestMatchers("/error").permitAll()
				// Only exist when springdoc is enabled (local profile or API_DOCS_ENABLED=true).
				.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(rs -> rs
				.jwt(jwt -> jwt.jwtAuthenticationConverter(MobilityJwt.authenticationConverter()))
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler));
		return http.build();
	}

	/**
	 * Browser origins allowed to call the API directly ({@code web-console.allowed-origins},
	 * WEB_CONSOLE_ORIGINS). Empty by default: the web console calls the API from its server side.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${web-console.allowed-origins:}") List<String> allowedOrigins) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		List<String> origins = allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
		if (!origins.isEmpty()) {
			CorsConfiguration config = new CorsConfiguration();
			config.setAllowedOrigins(origins);
			config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
			config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language", "Idempotency-Key"));
			config.setExposedHeaders(List.of("Location", "Retry-After", "Idempotent-Replayed"));
			config.setMaxAge(3600L);
			source.registerCorsConfiguration("/api/**", config);
		}
		return source;
	}

	@Bean
	AuthenticationEntryPoint problemAuthenticationEntryPoint(Problems problems) {
		return (request, response, ex) -> {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			problems.write(request, response, HttpStatus.UNAUTHORIZED, "auth.unauthorized");
		};
	}

	@Bean
	AccessDeniedHandler problemAccessDeniedHandler(Problems problems) {
		return (request, response, ex) -> problems.write(request, response, HttpStatus.FORBIDDEN, "auth.forbidden");
	}
}
