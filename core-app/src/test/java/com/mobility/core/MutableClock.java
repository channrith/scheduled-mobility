package com.mobility.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** System UTC time shifted by an adjustable offset. Tests that advance it must {@link #reset()}. */
public class MutableClock extends Clock {

	private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

	public void advance(Duration duration) {
		offset.updateAndGet(o -> o.plus(duration));
	}

	public void reset() {
		offset.set(Duration.ZERO);
	}

	@Override
	public Instant instant() {
		return Instant.now().plus(offset.get());
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}
}
