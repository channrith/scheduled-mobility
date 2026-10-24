package com.mobility.location.ping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.mobility.location.LocationProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A driver's GPS ping over HTTP (the WebSocket stream comes later). Stores the latest position in Redis, the contract
 * core-app's dispatch reads:
 * <ul>
 * <li>hash {@code driver:location:<userId>}: {@code lat}, {@code lng}, {@code recordedAt} (epoch ms), {@code accuracy}</li>
 * <li>GEO set {@code driver:positions}, member = user id</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/locations")
class LocationController {

	static final String HASH_PREFIX = "driver:location:";

	static final String GEO_KEY = "driver:positions";

	/** A ping may be a little late (bad network), but not stale or from the future. */
	static final Duration MAX_AGE = Duration.ofMinutes(10);

	static final Duration MAX_AHEAD = Duration.ofMinutes(1);

	private final StringRedisTemplate redis;

	private final LocationProperties properties;

	private final Clock clock;

	LocationController(StringRedisTemplate redis, LocationProperties properties) {
		this.redis = redis;
		this.properties = properties;
		this.clock = Clock.systemUTC();
	}

	/** Cambodia, with a margin. */
	record PingRequest(@NotNull @DecimalMin("9.5") @DecimalMax("15.0") Double latitude,
			@NotNull @DecimalMin("102.0") @DecimalMax("108.0") Double longitude, @PositiveOrZero Double accuracyMeters,
			Instant recordedAt) {
	}

	@PostMapping
	ResponseEntity<Map<String, Object>> ping(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PingRequest request) {
		Instant now = clock.instant();
		Instant at = request.recordedAt() == null ? now : request.recordedAt();
		if (at.isAfter(now.plus(MAX_AHEAD)) || at.isBefore(now.minus(MAX_AGE))) {
			return ResponseEntity.badRequest().body(Map.of("code", "location.recorded-at-invalid"));
		}
		String userId = jwt.getSubject();
		String key = HASH_PREFIX + userId;
		Object previous = redis.opsForHash().get(key, "recordedAt");
		if (previous != null && Long.parseLong(previous.toString()) > at.toEpochMilli()) {
			// An older ping arriving late never overwrites a newer position.
			return ResponseEntity.accepted().body(Map.of("stored", false));
		}
		redis.opsForHash().putAll(key, Map.of("lat", request.latitude().toString(), "lng", request.longitude().toString(),
				"recordedAt", Long.toString(at.toEpochMilli()), "accuracy",
				request.accuracyMeters() == null ? "" : request.accuracyMeters().toString()));
		redis.expire(key, properties.positionTtl());
		redis.opsForGeo().add(GEO_KEY, new Point(request.longitude(), request.latitude()), userId);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("stored", true));
	}
}
