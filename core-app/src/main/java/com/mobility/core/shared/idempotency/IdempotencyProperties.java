package com.mobility.core.shared.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param ttl how long responses to authenticated callers are replayable
 * @param anonymousTtl shorter replay window for unauthenticated endpoints (auth/*), whose cached
 * responses contain fresh tokens
 * @param lockTtl how long a request may be in progress before its key is released
 * @param encryptionKey base64 AES-256 key; cached responses are encrypted at rest in Redis
 */
@ConfigurationProperties("idempotency")
public record IdempotencyProperties(
		@DefaultValue("24h") Duration ttl,
		@DefaultValue("10m") Duration anonymousTtl,
		@DefaultValue("60s") Duration lockTtl,
		@DefaultValue("12MB") DataSize maxBodySize,
		String encryptionKey) {
}
