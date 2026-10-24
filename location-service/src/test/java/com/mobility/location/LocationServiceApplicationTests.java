package com.mobility.location;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LocationServiceApplicationTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("location.jwt.public-key", TestKeys::publicPem);
	}

	@Test
	void contextLoads() {
	}
}
