package com.mobility.location;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("staging")
class StagingProfileTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("mobility.jwt.public-key", TestKeys::publicPem);
	}

	@Test
	void contextLoadsWithStagingProfile() {
	}
}
