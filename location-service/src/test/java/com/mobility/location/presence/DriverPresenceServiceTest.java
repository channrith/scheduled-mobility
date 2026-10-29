package com.mobility.location.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.mobility.location.driver.DriverAccessResolver;
import com.mobility.location.driver.DriverIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(OutputCaptureExtension.class)
class DriverPresenceServiceTest {

    final DriverAccessResolver resolver = mock(DriverAccessResolver.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final UUID driverId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
            .subject(userId.toString()).build();
    final String key = "location:driver:{" + driverId + "}:presence";
    final DriverPresenceService service = new DriverPresenceService(resolver, redis, Duration.ofSeconds(90));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(resolver.resolve(jwt)).thenReturn(new DriverIdentity(driverId, userId, "PENDING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "APPROVED", "SUSPENDED", "REJECTED"})
    void validTimestampIsOnlineRegardlessOfOnboardingAndUsesConfiguredTimeout(String status) {
        when(resolver.resolve(jwt)).thenReturn(new DriverIdentity(driverId, userId, status));
        String receivedAt = "2026-10-29T07:35:47.099281Z";
        when(values.get(key)).thenReturn(receivedAt);

        assertThat(service.get(jwt)).isEqualTo(new DriverPresenceResponse(true, Instant.parse(receivedAt), 90));
        verifyOneRead();
    }

    @Test
    void missingStateIsOfflineWithoutCreatingIt() {
        when(values.get(key)).thenReturn(null);

        assertThat(service.get(jwt)).isEqualTo(new DriverPresenceResponse(false, null, 90));
        verifyOneRead();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "sensitive-redis-value", "2026-10-29T07:35:47"})
    void malformedStateReturns503WithoutLoggingStoredValues(String timestamp, CapturedOutput output) {
        when(values.get(key)).thenReturn(timestamp);

        assertUnavailable();
        assertThat(output.getOut()).contains("invalid timestamp").doesNotContain("sensitive-redis-value", "test-token");
        verifyOneRead();
    }

    @Test
    void redisFailureReturns503WithoutLoggingItsMessage(CapturedOutput output) {
        when(values.get(key)).thenThrow(new RedisConnectionFailureException("sensitive-redis-value"));

        assertUnavailable();
        assertThat(output.getOut()).contains("Redis read failed").doesNotContain("sensitive-redis-value", "test-token");
        verifyOneRead();
    }

    @Test
    void driverResolutionFailureIsPreservedWithoutAccessingRedis() {
        when(resolver.resolve(jwt)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));

        assertThatThrownBy(() -> service.get(jwt)).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(redis, values);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT0.5S", "PT60.5S"})
    void rejectsTimeoutsThatDoNotMatchWriterRequirements(String duration) {
        assertThatThrownBy(() -> new DriverPresenceService(resolver, redis, Duration.parse(duration)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> service.get(jwt)).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(503);
            assertThat(ex.getReason()).isEqualTo("Driver presence is temporarily unavailable");
        });
    }

    private void verifyOneRead() {
        verify(resolver).resolve(jwt);
        verify(redis).opsForValue();
        verify(values).get(key);
        verifyNoMoreInteractions(resolver, redis, values);
    }
}
