package com.mobility.location;

import com.mobility.platform.security.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LocationServiceApplicationTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("mobility.jwt.public-key", TestKeys::publicPem);
	}

	@Autowired
	ApplicationContext context;

	@Test
	void contextLoadsWithRequestIdLoggingFromPlatformSecurity() {
		assertThat(context.getBeansOfType(RequestIdFilter.class)).hasSize(1);
	}
}
