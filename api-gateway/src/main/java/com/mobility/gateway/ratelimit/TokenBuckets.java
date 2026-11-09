package com.mobility.gateway.ratelimit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.mobility.gateway.ratelimit.RateLimitProperties.Policy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Token buckets in Redis: one atomic script call per request ({@code ratelimit/token_bucket.lua}). */
@Component
class TokenBuckets {

	/** Allowed, or refused with the time until the next token. */
	record Decision(boolean allowed, Duration retryAfter) {

		static final Decision ALLOWED = new Decision(true, Duration.ZERO);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final RedisScript<List<Long>> SCRIPT = (RedisScript) RedisScript
		.of(new ClassPathResource("ratelimit/token_bucket.lua"), List.class);

	private final ReactiveStringRedisTemplate redis;

	private final String keyPrefix;

	TokenBuckets(ReactiveStringRedisTemplate redis, RateLimitProperties properties) {
		this.redis = redis;
		this.keyPrefix = properties.keyPrefix();
	}

	/**
	 * Opens the Redis connection and loads the script, so the first request after a start doesn't pay for either
	 * (Lettuce connects lazily; on a cold JVM that first connect can miss the per-request timeout).
	 */
	Mono<Void> warmUp() {
		ByteBuffer script = ByteBuffer.wrap(SCRIPT.getScriptAsString().getBytes(StandardCharsets.UTF_8));
		return redis.execute(connection -> connection.scriptingCommands().scriptLoad(script)
				.then(connection.ping()))
			.then();
	}

	/** Takes a token from the caller's bucket for this policy. Errors (Redis down) are the caller's to handle. */
	Mono<Decision> take(Policy policy, String caller) {
		List<String> keys = List.of(keyPrefix + policy.id() + ":" + caller);
		List<String> args = List.of(Integer.toString(policy.burst()), Double.toString(policy.tokensPerMilli()));
		return redis.execute(SCRIPT, keys, args)
			.reduce(new ArrayList<Long>(), (all, part) -> {
				all.addAll(part);
				return all;
			})
			.map(result -> result.get(0) == 1L ? Decision.ALLOWED
					: new Decision(false, Duration.ofMillis(Math.max(1, result.get(1)))));
	}
}
