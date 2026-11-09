package com.mobility.location.ping;

import java.time.Instant;
import java.util.List;

import com.mobility.location.LocationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Stores driver pings in Redis, the contract core-app's dispatch reads:
 * <ul>
 * <li>hash {@code driver:location:<userId>}: {@code lat}, {@code lng}, {@code recordedAt} (epoch ms), {@code accuracy},
 * {@code speed} (m/s), {@code heading} (degrees); empty string when the app didn't send it</li>
 * <li>GEO set {@code driver:positions}, member = user id</li>
 * <li>hash {@code driver:state:<userId>}: {@code lastSeen} (epoch ms, any authenticated ping, even a dropped one);
 * core-app writes {@code status} and {@code tripId} there</li>
 * </ul>
 * The filter, the "never overwrite a newer position" rule and the write run as one Lua script, so concurrent pings
 * can't race.
 */
@Service
class PingIngestion {

	static final String HASH_PREFIX = "driver:location:";

	static final String GEO_KEY = "driver:positions";

	static final String STATE_PREFIX = "driver:state:";

	private static final RedisScript<String> SCRIPT = RedisScript.of(new ClassPathResource("ping/store_ping.lua"),
			String.class);

	private final StringRedisTemplate redis;

	private final LocationProperties properties;

	PingIngestion(StringRedisTemplate redis, LocationProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/** {@code reason} is null when stored, else {@code stale}, {@code accuracy} or {@code speed}. */
	record Outcome(boolean stored, String reason) {
	}

	Outcome ingest(String userId, Ping ping, Instant now) {
		LocationProperties.Filter filter = properties.filter();
		String result = redis.execute(SCRIPT, List.of(HASH_PREFIX + userId, GEO_KEY, STATE_PREFIX + userId), userId,
				ping.latitude().toString(), ping.longitude().toString(), Long.toString(ping.recordedAt().toEpochMilli()),
				text(ping.accuracyMeters()), text(ping.speedMps()), text(ping.headingDegrees()),
				Long.toString(now.toEpochMilli()), Long.toString(properties.positionTtl().toSeconds()),
				Double.toString(filter.maxAccuracyMeters()), Double.toString(filter.maxSpeedKmh() / 3.6),
				Long.toString(filter.speedCheckWindow().toMillis()));
		return "stored".equals(result) ? new Outcome(true, null) : new Outcome(false, result);
	}

	private static String text(Double value) {
		return value == null ? "" : value.toString();
	}

	/** One GPS fix; {@code recordedAt} already resolved (the server's time when the app sent none). */
	record Ping(Double latitude, Double longitude, Double accuracyMeters, Double speedMps, Double headingDegrees,
			Instant recordedAt) {
	}
}
