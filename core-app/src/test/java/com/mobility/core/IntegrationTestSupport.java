package com.mobility.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.client.RestTestClient;

@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestSupport {

	@Bean
	@Primary
	CapturingSmsSender capturingSmsSender() {
		return new CapturingSmsSender();
	}

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock();
	}

	@Bean
	CapturedEvents capturedEvents() {
		return new CapturedEvents();
	}

	@Bean
	AuthTestClient authTestClient(@Autowired RestTestClient client, CapturingSmsSender sms) {
		return new AuthTestClient(client, sms);
	}
}
