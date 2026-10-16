package com.mobility.location.tracking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisDriverLocationRepository
        implements DriverLocationRepository {

    private final StringRedisTemplate redis;
    private final Duration retention;
    private final DefaultRedisScript<Long> saveScript;
    private final String eventsChannel;

    public RedisDriverLocationRepository(
            StringRedisTemplate redis,
            @Value("${location.tracking.retention:24h}")
            Duration retention,
            @Value("${location.tracking.events-channel}")
            String eventsChannel
    ) {
        if (retention.getSeconds() < 1) {
            throw new IllegalArgumentException(
                    "Location retention must be at least one second"
            );
        }

        if (eventsChannel.isBlank()) {
            throw new IllegalArgumentException(
                    "Location events channel must not be blank"
            );
        }

        this.redis = redis;
        this.retention = retention;
        this.eventsChannel = eventsChannel;

        this.saveScript = new DefaultRedisScript<>();
        this.saveScript.setLocation(
                new ClassPathResource("redis/save-driver-location.lua")
        );
        this.saveScript.setResultType(Long.class);
    }

    @Override
    public boolean saveIfNewer(
            UUID driverId,
            UpdateDriverLocationRequest location,
            Instant receivedAt
    ) {
        String key = "location:driver:{" + driverId + "}:latest";
        Instant recordedAt = location.recordedAt();

        Long result = redis.execute(
                saveScript,
                List.of(key),
                Long.toString(recordedAt.getEpochSecond()),
                Integer.toString(recordedAt.getNano()),
                Double.toString(location.latitude()),
                Double.toString(location.longitude()),
                Double.toString(location.accuracyMeters()),
                recordedAt.toString(),
                receivedAt.toString(),
                Long.toString(retention.getSeconds())
        );

        if (result == null) {
            throw new IllegalStateException(
                    "Redis returned no location storage result"
            );
        }

        return result == 1L;
    }

    @Override
    public Optional<StoredDriverLocation> findLatest(UUID driverId) {
        String key = "location:driver:{" + driverId + "}:latest";

        Map<Object, Object> fields = redis.opsForHash().entries(key);

        if (fields.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                new StoredDriverLocation(
                        Double.parseDouble(required(fields, "latitude")),
                        Double.parseDouble(required(fields, "longitude")),
                        Double.parseDouble(required(fields, "accuracyMeters")),
                        Instant.parse(required(fields, "recordedAt")),
                        Instant.parse(required(fields, "receivedAt"))
                )
        );
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

    @Override
    public SaveResult compareAndSave(
            UUID driverId,
            UpdateDriverLocationRequest location,
            Instant receivedAt,
            Optional<StoredDriverLocation> expectedPrevious
    ) {
        String key = "location:driver:{" + driverId + "}:latest";
        Instant recordedAt = location.recordedAt();

        String expectedRecordedAt = expectedPrevious
                .map(previous -> previous.recordedAt().toString())
                .orElse("");

        Long result = redis.execute(
                saveScript,
                List.of(key),
                Long.toString(recordedAt.getEpochSecond()),   // ARGV[1]
                Integer.toString(recordedAt.getNano()),       // ARGV[2]
                Double.toString(location.latitude()),        // ARGV[3]
                Double.toString(location.longitude()),       // ARGV[4]
                Double.toString(location.accuracyMeters()),   // ARGV[5]
                recordedAt.toString(),                       // ARGV[6]
                receivedAt.toString(),                       // ARGV[7]
                Long.toString(retention.getSeconds()),       // ARGV[8]
                expectedRecordedAt,                         // ARGV[9]
                eventsChannel,                              // ARGV[10]
                driverId.toString(),                        // ARGV[11]
                UUID.randomUUID().toString()                // ARGV[12]
        );

        if (result == null) {
            throw new IllegalStateException(
                    "Redis returned no location storage result"
            );
        }

        return switch (result.intValue()) {
            case 1 -> SaveResult.STORED;
            case 0 -> SaveResult.IGNORED;
            case -1 -> SaveResult.CONFLICT;
            default -> throw new IllegalStateException(
                    "Unexpected Redis location storage result"
            );
        };
    }
}