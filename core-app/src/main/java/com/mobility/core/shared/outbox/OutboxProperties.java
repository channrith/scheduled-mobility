package com.mobility.core.shared.outbox;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param exchange RabbitMQ topic exchange the relay publishes to
 * @param batchSize events per relay run
 * @param confirmTimeout how long to wait for RabbitMQ's publisher confirm
 * @param maxBackoff longest wait between retries of one event
 * @param retention published events are deleted after this
 */
@Validated
@ConfigurationProperties("outbox")
public record OutboxProperties(
		@DefaultValue("mobility.events") String exchange,
		@DefaultValue("100") @Min(1) @Max(1000) int batchSize,
		@DefaultValue("5s") Duration confirmTimeout,
		@DefaultValue("5m") Duration maxBackoff,
		@DefaultValue("7d") Duration retention) {
}
