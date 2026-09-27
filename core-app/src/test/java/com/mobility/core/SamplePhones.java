package com.mobility.core;

import java.util.concurrent.atomic.AtomicInteger;

/** Unique Cambodian mobile numbers so tests never share Redis rate-limit state. */
public final class SamplePhones {

	private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 900_000));

	private SamplePhones() {
	}

	public static String next() {
		return "+85597" + String.format("%07d", SEQ.incrementAndGet() % 10_000_000);
	}
}
