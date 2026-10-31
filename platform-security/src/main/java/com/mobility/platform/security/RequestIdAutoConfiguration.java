package com.mobility.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;

/** Every servlet service on this module logs request IDs without wiring anything. The gateway has its own filter. */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class RequestIdAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	RequestIdFilter requestIdFilter() {
		return new RequestIdFilter();
	}
}
