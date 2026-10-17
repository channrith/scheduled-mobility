package com.mobility.core.pricing;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out the TEST_nn cities configured in application-test.yml, so each test class prices its own city and
 * the latest rule version of one test never applies to another.
 */
public final class TestCities {

	private static final AtomicInteger NEXT = new AtomicInteger();

	private TestCities() {
	}

	public static String next() {
		int n = NEXT.incrementAndGet();
		if (n > 60) {
			throw new IllegalStateException("Add more TEST_nn cities to application-test.yml");
		}
		return "TEST_%02d".formatted(n);
	}
}
