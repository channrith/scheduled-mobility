package com.mobility.core.shared.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the events exchange; consumers bind their own queues to it (e.g. {@code booking.#}). */
@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

	@Bean
	TopicExchange eventsExchange(OutboxProperties properties) {
		return new TopicExchange(properties.exchange(), true, false);
	}
}
