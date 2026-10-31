package com.mobility.core.dispatch.risk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Latest driver positions, written by location-service. Contract (keep both sides in step):
 * <ul>
 * <li>hash {@code driver:location:<userId>} with {@code lat}, {@code lng} (degrees) and {@code recordedAt} (epoch
 * milliseconds)</li>
 * <li>GEO set {@code driver:positions}, member = user id (for "drivers near" queries)</li>
 * </ul>
 * Keyed by the driver's user id: location-service only knows the login, not the driver profile.
 */
@Component
public class DriverPositions {

	public static final String HASH_PREFIX = "driver:location:";

	public static final String GEO_KEY = "driver:positions";

	public record Position(double latitude, double longitude, Instant recordedAt) {
	}

	private final StringRedisTemplate redis;

	DriverPositions(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public Optional<Position> latest(UUID driverUserId) {
		Map<Object, Object> fields = redis.opsForHash().entries(HASH_PREFIX + driverUserId);
		Object lat = fields.get("lat");
		Object lng = fields.get("lng");
		Object at = fields.get("recordedAt");
		if (lat == null || lng == null || at == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(new Position(Double.parseDouble(lat.toString()), Double.parseDouble(lng.toString()),
					Instant.ofEpochMilli(Long.parseLong(at.toString()))));
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/** For tests and tools: writes a position the way location-service does. */
	public void record(UUID driverUserId, double latitude, double longitude, Instant recordedAt) {
		redis.opsForHash().putAll(HASH_PREFIX + driverUserId, Map.of("lat", Double.toString(latitude), "lng",
				Double.toString(longitude), "recordedAt", Long.toString(recordedAt.toEpochMilli())));
		redis.opsForGeo().add(GEO_KEY, new org.springframework.data.geo.Point(longitude, latitude), driverUserId.toString());
	}

	public void forget(List<UUID> driverUserIds) {
		redis.delete(driverUserIds.stream().map(id -> HASH_PREFIX + id).toList());
	}
}
