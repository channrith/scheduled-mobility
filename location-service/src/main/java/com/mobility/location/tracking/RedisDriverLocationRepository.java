package com.mobility.location.tracking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisDriverLocationRepository
        implements DriverLocationRepository {

    private final StringRedisTemplate redis;
    private final Duration retention;
    private final Duration presenceTimeout;
    private final String eventsChannel;
    private final DefaultRedisScript<Long> saveScript;

    public RedisDriverLocationRepository(
            StringRedisTemplate redis,

            @Value("${location.tracking.retention:24h}")
            Duration retention,

            @Value("${location.tracking.events-channel}")
            String eventsChannel,

            @Value("${location.presence.timeout:60s}")
            Duration presenceTimeout
    ) {
        if (retention.getSeconds() < 1) {
            throw new IllegalArgumentException(
                    "Location retention must be at least one second"
            );
        }

        if (presenceTimeout.getSeconds() < 1
                || presenceTimeout.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Presence timeout must be a positive whole number of seconds"
            );
        }

        if (eventsChannel.isBlank()) {
            throw new IllegalArgumentException(
                    "Location events channel must not be blank"
            );
        }

        this.redis = redis;
        this.retention = retention;
        this.presenceTimeout = presenceTimeout;
        this.eventsChannel = eventsChannel;

        this.saveScript = new DefaultRedisScript<>();
        this.saveScript.setLocation(
                new ClassPathResource("redis/save-driver-location.lua")
        );
        this.saveScript.setResultType(Long.class);
    }

    /**
     * Compatibility method for callers that only require timestamp ordering.
     *
     * Returns true when stored, false when the incoming measurement
     * is equal to or older than the stored measurement.
     *
     * DriverLocationService should continue using compareAndSave()
     * directly to preserve its jump-validation baseline.
     */
    @Override
    public boolean saveIfNewer(
            UUID driverId,
            UpdateDriverLocationRequest location,
            Instant receivedAt
    ) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<StoredDriverLocation> previous =
                    findLatest(driverId);

            SaveResult result = compareAndSave(
                    driverId,
                    location,
                    receivedAt,
                    previous
            );

            switch (result) {
                case STORED:
                    return true;

                case IGNORED:
                    return false;

                case CONFLICT:
                    // Reload the baseline before retrying.
                    break;
            }
        }

        throw new OptimisticLockingFailureException(
                "Location changed concurrently; retry the update"
        );
    }

    /**
     * Reads the latest stored location.
     * Reading does not refresh location retention or presence.
     */
    @Override
    public Optional<StoredDriverLocation> findLatest(UUID driverId) {
        Map<Object, Object> fields = redis.opsForHash()
                .entries(locationKey(driverId));

        if (fields.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                new StoredDriverLocation(
                        Double.parseDouble(
                                required(fields, "latitude")
                        ),
                        Double.parseDouble(
                                required(fields, "longitude")
                        ),
                        Double.parseDouble(
                                required(fields, "accuracyMeters")
                        ),
                        Instant.parse(
                                required(fields, "recordedAt")
                        ),
                        Instant.parse(
                                required(fields, "receivedAt")
                        )
                )
        );
    }

    /**
     * Saves a validated measurement only when the previously read
     * location is still current and the incoming timestamp is newer.
     *
     * On acceptance, the Lua script:
     * - Saves the latest location.
     * - Refreshes location retention.
     * - Refreshes the separate presence key.
     * - Publishes the location event.
     */
    @Override
    public SaveResult compareAndSave(
            UUID driverId,
            UpdateDriverLocationRequest location,
            Instant receivedAt,
            Optional<StoredDriverLocation> expectedPrevious
    ) {
        return compareAndSave(driverId, null, location, receivedAt, expectedPrevious);
    }

    @Override
    public SaveResult compareAndSave(UUID driverId, UUID userId,
            UpdateDriverLocationRequest location, Instant receivedAt,
            Optional<StoredDriverLocation> expectedPrevious) {
        Instant recordedAt = location.recordedAt();

        String expectedRecordedAt = expectedPrevious
                .map(previous -> previous.recordedAt().toString())
                .orElse("");

        Long result = redis.execute(
                saveScript,

                List.of(
                        locationKey(driverId),  // KEYS[1]
                        presenceKey(driverId),  // KEYS[2]
                        userId == null ? locationKey(driverId) : "driver:location:" + userId,
                        userId == null ? locationKey(driverId) : "driver:positions"
                ),

                Long.toString(recordedAt.getEpochSecond()),  // ARGV[1]
                Integer.toString(recordedAt.getNano()),      // ARGV[2]
                Double.toString(location.latitude()),        // ARGV[3]
                Double.toString(location.longitude()),       // ARGV[4]
                Double.toString(location.accuracyMeters()),  // ARGV[5]
                recordedAt.toString(),                       // ARGV[6]
                receivedAt.toString(),                       // ARGV[7]
                Long.toString(retention.getSeconds()),       // ARGV[8]
                expectedRecordedAt,                         // ARGV[9]
                eventsChannel,                              // ARGV[10]
                driverId.toString(),                        // ARGV[11]
                UUID.randomUUID().toString(),                // ARGV[12]
                Long.toString(presenceTimeout.getSeconds()), // ARGV[13]
                userId == null ? "" : userId.toString(),      // ARGV[14]
                Long.toString(recordedAt.toEpochMilli())     // ARGV[15]
        );

        if (result == null) {
            throw new IllegalStateException(
                    "Redis returned no location storage result"
            );
        }

        if (result == 1L) {
            return SaveResult.STORED;
        }

        if (result == 0L) {
            return SaveResult.IGNORED;
        }

        if (result == -1L) {
            return SaveResult.CONFLICT;
        }

        throw new IllegalStateException(
                "Unexpected Redis location storage result: " + result
        );
    }

    private static String locationKey(UUID driverId) {
        return "location:driver:{" + driverId + "}:latest";
    }

    private static String presenceKey(UUID driverId) {
        return "location:driver:{" + driverId + "}:presence";
    }

    private static String required(
            Map<Object, Object> fields,
            String name
    ) {
        Object value = fields.get(name);

        if (value == null) {
            throw new IllegalStateException(
                    "Stored location is missing field: " + name
            );
        }

        return value.toString();
    }
}