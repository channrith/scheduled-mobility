package com.mobility.core.dispatch.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.risk.TravelEstimator;
import com.mobility.core.driver.VehicleClass;
import org.junit.jupiter.api.Test;

class AssignmentGuardTest {
    final Instant midnight = Instant.parse("2026-11-11T17:00:00Z");
    final TravelEstimator travel = mock(TravelEstimator.class);
    final DispatchProperties properties = mock(DispatchProperties.class);

    AssignmentGuard guard() {
        when(properties.planning()).thenReturn(new DispatchProperties.Planning(Duration.ofMinutes(10), 10));
        when(travel.between(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(Duration.ofMinutes(15));
        return new AssignmentGuard(null, null, null, travel, properties, null);
    }

    @Test
    void overlapAcrossLocalMidnightIsRejected() {
        assertThat(guard().conflicts(trip(midnight.minusSeconds(600), true), trip(midnight, true))).isTrue();
    }

    @Test
    void nonoverlappingTripsStillNeedTravelAndBufferTime() {
        var first = trip(midnight, true);
        assertThat(guard().conflicts(first, trip(midnight.plusSeconds(3000), true))).isTrue();
        assertThat(guard().conflicts(first, trip(midnight.plusSeconds(3300), true))).isFalse();
    }

    @Test
    void exactBoundaryIsAllowedInEitherOrder() {
        var a = trip(midnight, true);
        var b = trip(midnight.plusSeconds(3300), true);
        assertThat(guard().conflicts(a, b)).isFalse();
        assertThat(guard().conflicts(b, a)).isFalse();
    }

    @Test
    void unknownMeteredReleaseTimeNeverBecomesAnInventedFreeSlot() {
        assertThat(guard().conflicts(trip(midnight, false), trip(midnight.plusSeconds(86400), true))).isTrue();
        assertThat(guard().conflicts(trip(midnight, true), trip(midnight.plusSeconds(86400), false))).isTrue();
    }

    private Trips.TripInfo trip(Instant pickup, boolean destination) {
        return new Trips.TripInfo(UUID.randomUUID(), UUID.randomUUID(), BookingType.SCHEDULED,
                TripStatus.ASSIGNED, pickup, Duration.ofMinutes(30), VehicleClass.CAR,
                11.55, 104.92, destination ? 11.56 : null, destination ? 104.93 : null,
                null, null, UUID.randomUUID(), 0);
    }
}
