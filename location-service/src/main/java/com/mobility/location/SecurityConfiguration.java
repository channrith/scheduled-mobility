package com.mobility.location;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. Verifying core-app's tokens (keys from {@code mobility.jwt.*}, issuer, roles) is
 * platform-security's job; only drivers may ping, everything else is closed.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain security(HttpSecurity http) throws Exception {
		return http.csrf(c -> c.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info", "/error").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/locations").hasRole("DRIVER")
				.anyRequest().denyAll())
			.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
			.build();
	}
}
