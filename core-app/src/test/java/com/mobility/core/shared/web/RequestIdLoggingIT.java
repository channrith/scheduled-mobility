package com.mobility.core.shared.web;

import com.mobility.core.IntegrationTest;
import com.mobility.platform.security.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Request IDs in logs come from platform-security's auto-configuration; nothing is wired in core-app. */
@IntegrationTest
class RequestIdLoggingIT {

	@Autowired
	ApplicationContext context;

	@Test
	void requestIdFilterIsRegisteredOnce() {
		assertThat(context.getBeansOfType(RequestIdFilter.class)).hasSize(1);
	}
}
