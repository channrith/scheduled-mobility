package com.mobility.platform.security;

import java.time.Instant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MobilityJwtAutoConfigurationTest {

	final TestTokens core = new TestTokens("key-1");

	final AutoConfigurations config = AutoConfigurations.of(MobilityJwtAutoConfiguration.class, RequestIdAutoConfiguration.class);

	@Test
	void servletServiceGetsADecoderAndTheRolesConverterFromSettings() {
		new WebApplicationContextRunner().withConfiguration(config)
			.withPropertyValues("mobility.jwt.public-key=" + core.publicPem())
			.run(context -> {
				String token = core.token("driver-1", MobilityJwt.ISSUER, Instant.now().plusSeconds(600), "DRIVER");
				assertThat(context.getBean(JwtDecoder.class).decode(token).getSubject()).isEqualTo("driver-1");
				assertThat(context).hasSingleBean(JwtAuthenticationConverter.class).hasSingleBean(RequestIdFilter.class);
			});
	}

	@Test
	void reactiveServiceGetsAReactiveDecoderFromTheJwksUrl() throws Exception {
		HttpServer jwks = TestTokens.jwksServer(core);
		try {
			new ReactiveWebApplicationContextRunner().withConfiguration(config)
				.withPropertyValues("mobility.jwt.jwks-uri=" + TestTokens.url(jwks))
				.run(context -> {
					String token = core.token("user-1", MobilityJwt.ISSUER, Instant.now().plusSeconds(600));
					assertThat(context.getBean(ReactiveJwtDecoder.class).decode(token).block().getSubject()).isEqualTo("user-1");
					assertThat(context).doesNotHaveBean(JwtDecoder.class).doesNotHaveBean(RequestIdFilter.class);
				});
		}
		finally {
			jwks.stop(0);
		}
	}

	@Test
	void aServiceWithItsOwnDecoderKeepsIt() {
		JwtDecoder own = mock(JwtDecoder.class);
		new WebApplicationContextRunner().withConfiguration(config)
			.withBean(JwtDecoder.class, () -> own)
			.run(context -> assertThat(context.getBean(JwtDecoder.class)).isSameAs(own));
	}

	@Test
	void aServiceWithoutSettingsFailsAtStartupNamingThem() {
		new WebApplicationContextRunner().withConfiguration(config)
			.run(context -> assertThat(context).hasFailed()
				.getFailure().rootCause().hasMessageContaining("Set exactly one of JWT_JWKS_URI"));
	}

	@Test
	void nothingForNonWebApplications() {
		new ApplicationContextRunner().withConfiguration(config)
			.run(context -> assertThat(context).doesNotHaveBean(JwtDecoder.class).doesNotHaveBean(ReactiveJwtDecoder.class));
	}
}
