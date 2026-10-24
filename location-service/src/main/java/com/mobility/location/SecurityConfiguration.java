package com.mobility.location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/** Accepts core-app's access tokens (RS256, issuer {@code mobility-core}); roles come from the {@code roles} claim. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain security(HttpSecurity http) throws Exception {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			List<String> roles = jwt.getClaimAsStringList("roles");
			Collection<GrantedAuthority> authorities = roles == null ? List.of()
					: roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
			return authorities;
		});
		return http.csrf(c -> c.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info", "/error").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/locations").hasRole("DRIVER")
				.anyRequest().denyAll())
			.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(LocationProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey(properties.jwt())).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(java.time.Duration.ofSeconds(30)),
				new JwtIssuerValidator(properties.jwt().issuer())));
		return decoder;
	}

	static RSAPublicKey publicKey(LocationProperties.Jwt jwt) {
		boolean inline = StringUtils.hasText(jwt.publicKey());
		boolean file = StringUtils.hasText(jwt.publicKeyFile());
		if (inline == file) {
			throw new IllegalStateException("Set exactly one of JWT_PUBLIC_KEY (PEM) or JWT_PUBLIC_KEY_FILE (path): core-app's public key");
		}
		String pem;
		try {
			pem = inline ? jwt.publicKey() : Files.readString(Path.of(jwt.publicKeyFile().strip()));
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot read JWT_PUBLIC_KEY_FILE", e);
		}
		byte[] der = Base64.getDecoder().decode(pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", ""));
		try {
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Invalid JWT public key (expected X.509 PEM)", e);
		}
	}
}
