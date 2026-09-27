package com.mobility.core.shared.idempotency;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only authenticated endpoints that count how often they actually execute. */
@RestController
@RequestMapping("/api/v1/test/idempotency")
class IdempotencyProbeController {

	static final AtomicInteger executions = new AtomicInteger();

	static final AtomicInteger failuresLeft = new AtomicInteger();

	@PostMapping("/count")
	Map<String, Object> count(@RequestBody Map<String, Object> body) {
		return Map.of("execution", executions.incrementAndGet(), "echo", body);
	}

	@PostMapping("/flaky")
	Map<String, Object> flaky(@RequestBody Map<String, Object> body) {
		if (failuresLeft.getAndDecrement() > 0) {
			throw new IllegalStateException("simulated failure");
		}
		return Map.of("execution", executions.incrementAndGet());
	}
}
