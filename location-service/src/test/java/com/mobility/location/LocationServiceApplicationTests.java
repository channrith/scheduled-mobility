package com.mobility.location;

import com.mobility.platform.security.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.context.annotation.Import(LocationTestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("local")
class LocationServiceApplicationTests {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        JwtTestSupport.register(registry);
    }

	@Autowired
	ApplicationContext context;

	@Test
	void contextLoadsWithRequestIdLoggingFromPlatformSecurity() {
		assertThat(context.getBeansOfType(RequestIdFilter.class)).hasSize(1);
	}
}
