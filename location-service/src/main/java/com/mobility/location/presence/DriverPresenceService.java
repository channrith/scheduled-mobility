package com.mobility.location.presence;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.mobility.location.driver.DriverAccessResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DriverPresenceService {

    private static final Logger log =
            LoggerFactory.getLogger(DriverPresenceService.class);

    private final DriverAccessResolver driverResolver;
    private final StringRedisTemplate redis;
    private final long timeoutSeconds;

    public DriverPresenceService(
            DriverAccessResolver driverResolver,
            StringRedisTemplate redis,
            @Value("${location.presence.timeout:60s}")
            Duration timeout
    ) {
        if (timeout.getSeconds() < 1 || timeout.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Presence timeout must be a positive whole number of seconds"
            );
        }

        this.driverResolver = driverResolver;
        this.redis = redis;
        this.timeoutSeconds = timeout.getSeconds();
    }

    public DriverPresenceResponse get(Jwt jwt) {
        // Resolving a registered driver does not require APPROVED onboarding.
        var driver = driverResolver.resolve(jwt);
        String key = "location:driver:{" + driver.driverId() + "}:presence";
        String receivedAt;

        try {
            receivedAt = redis.opsForValue().get(key);
        } catch (RuntimeException ex) {
            // Exception messages and causes may contain Redis keys or values.
            log.warn("Driver presence Redis read failed ({})",
                    ex.getClass().getSimpleName());
            throw unavailable();
        }

        if (receivedAt == null) {
            return new DriverPresenceResponse(false, null, timeoutSeconds);
        }

        try {
            return new DriverPresenceResponse(
                    true, Instant.parse(receivedAt), timeoutSeconds
            );
        } catch (DateTimeParseException ex) {
            log.warn("Driver presence contains an invalid timestamp");
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Driver presence is temporarily unavailable"
        );
    }
}
