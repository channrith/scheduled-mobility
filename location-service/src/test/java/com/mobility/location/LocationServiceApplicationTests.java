package com.mobility.location;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class LocationServiceApplicationTests {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        JwtTestSupport.register(registry);
    }

	@Test
	void contextLoads() {
	}
}
