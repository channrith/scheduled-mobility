package com.mobility.gateway.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The warm-up must never hold up startup (and with it readiness), however long Redis takes. */
class RedisWarmUpTest {

	static RateLimitProperties properties(boolean enabled) {
		return new RateLimitProperties(enabled, RateLimitProperties.Mode.ENFORCE, Duration.ofMillis(100), "rl:",
				List.of());
	}

	@Test
	void aSlowSynchronousConnectDoesNotBlockTheStartupThread() throws InterruptedException {
		TokenBuckets buckets = mock(TokenBuckets.class);
		CountDownLatch started = new CountDownLatch(1);
		// Like Lettuce's first connect: blocks the subscribing thread.
		when(buckets.warmUp()).thenReturn(Mono.fromRunnable(() -> {
			started.countDown();
			sleep(Duration.ofSeconds(5));
		}));

		long before = System.nanoTime();
		new RedisWarmUp(properties(true), buckets).warmUp();

		assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofMillis(500));
		assertThat(started.await(2, TimeUnit.SECONDS)).as("the warm-up still runs, in the background").isTrue();
	}

	@Test
	void nothingRunsWhenLimitsAreOff() {
		TokenBuckets buckets = mock(TokenBuckets.class);

		new RedisWarmUp(properties(false), buckets).warmUp();

		verify(buckets, never()).warmUp();
	}

	static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
