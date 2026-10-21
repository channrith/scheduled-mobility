package com.mobility.core.driver.availability;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.shared.web.ApiException;
import com.mobility.core.driver.DriverAvailabilityEligibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Live driver choice in Redis; GPS freshness remains separate. */
@Service
class DriverAvailabilityService {

	private static final Logger log = LoggerFactory.getLogger(DriverAvailabilityService.class);

	private final StringRedisTemplate redis;

	private final DriverAvailabilityEligibility eligibility;

	private final Clock clock;

	private static final DefaultRedisScript<Long> UPDATE = new DefaultRedisScript<>();

	static {
		UPDATE.setLocation(new ClassPathResource("redis/update-driver-availability.lua"));
		UPDATE.setResultType(Long.class);
	}

	DriverAvailabilityService(StringRedisTemplate redis, DriverAvailabilityEligibility eligibility, Clock clock) {
		this.redis = redis;
		this.eligibility = eligibility;
		this.clock = clock;
	}

	AvailabilityResponse get(UUID driverId) {
		return parse(read(driverId));
	}

	AvailabilityResponse update(UUID driverId, UpdateAvailabilityRequest request) {
		if (request.status() == AvailabilityStatus.AVAILABLE) {
			eligibility.requireEligible(driverId);
		}
		for (int attempt = 0; attempt < 4; attempt++) {
			Map<String, String> fields = read(driverId);
			AvailabilityResponse current = parse(fields);
			Instant now = clock.instant();
			String nextVersion = current.version() == Long.MAX_VALUE ? "" : Long.toString(current.version() + 1);
			Long result;
			try {
				result = redis.execute(UPDATE, List.of("driver:availability:" + driverId),
						fields.isEmpty() ? "missing" : "present", current.status().name(),
						fields.getOrDefault("version", "0"), fields.getOrDefault("updatedAt", ""),
						Long.toString(current.version()), request.expectedVersion().toString(),
						request.status().name(), nextVersion, now.toString());
			}
			catch (RuntimeException ex) {
				log.warn("Driver availability Redis update failed ({})", ex.getClass().getSimpleName());
				throw unavailable();
			}
			if (Long.valueOf(-1).equals(result)) {
				continue;
			}
			if (Long.valueOf(0).equals(result)) {
				throw conflict();
			}
			if (Long.valueOf(2).equals(result)) {
				return current;
			}
			if (Long.valueOf(1).equals(result) && !nextVersion.isEmpty()) {
				return new AvailabilityResponse(request.status(), current.version() + 1, now);
			}
			log.warn("Driver availability Redis update returned an invalid result");
			throw unavailable();
		}
		throw conflict();
	}

	private static ApiException conflict() {
		return new ApiException(HttpStatus.CONFLICT, "concurrent-update")
			.withHeader(HttpHeaders.CACHE_CONTROL, "no-store");
	}

	private Map<String, String> read(UUID driverId) {
		Map<String, String> fields;
		try {
			fields = redis.<String, String>opsForHash().entries("driver:availability:" + driverId);
		}
		catch (RuntimeException ex) {
			// Redis exception messages and causes can contain commands, keys or stored values.
			log.warn("Driver availability Redis read failed ({})", ex.getClass().getSimpleName());
			throw unavailable();
		}
		return fields;
	}

	private AvailabilityResponse parse(Map<String, String> fields) {
		if (fields.isEmpty()) {
			return new AvailabilityResponse(AvailabilityStatus.UNAVAILABLE, 0, null);
		}
		try {
			AvailabilityStatus status = AvailabilityStatus.valueOf(required(fields, "status"));
			long version = Long.parseLong(required(fields, "version"));
			if (version < 0) {
				throw new IllegalArgumentException();
			}
			Instant updatedAt = Instant.parse(required(fields, "updatedAt"));
			return new AvailabilityResponse(status, version, updatedAt);
		}
		catch (IllegalArgumentException | DateTimeException ex) {
			log.warn("Driver availability Redis hash contains invalid state");
			throw unavailable();
		}
	}

	private static String required(Map<String, String> fields, String name) {
		String value = fields.get(name);
		if (value == null) {
			throw new IllegalArgumentException();
		}
		return value;
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "availability.unavailable")
			.withHeader(HttpHeaders.CACHE_CONTROL, "no-store");
	}
}
