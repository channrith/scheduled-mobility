package com.mobility.core.booking.web;
import java.util.*;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.ride.*;
import com.mobility.core.booking.trip.*;
import com.mobility.core.identity.*;
import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PassengerTrackingControllerTest {
    @Test void riderAndBookerCanTrackOnlyAnActiveTrip() {
        var trips = mock(TripRepository.class); var rides = mock(RideRepository.class); var users = mock(CurrentUserProvider.class);
        var controller = new PassengerTrackingController(trips, rides, users);
        UUID id = UUID.randomUUID(), rideId = UUID.randomUUID(), rider = UUID.randomUUID(), booker = UUID.randomUUID(), driver = UUID.randomUUID();
        var trip = mock(Trip.class); var ride = mock(Ride.class);
        when(trips.findById(id)).thenReturn(Optional.of(trip)); when(trip.getRideId()).thenReturn(rideId);
        when(rides.findById(rideId)).thenReturn(Optional.of(ride)); when(ride.getRiderUserId()).thenReturn(rider); when(ride.getBookedBy()).thenReturn(booker);
        when(trip.getDriverId()).thenReturn(driver);
        for (UUID user : List.of(rider, booker)) {
            when(users.require()).thenReturn(new CurrentUser(user, Set.of(Role.PASSENGER), Map.of(), Language.DEFAULT));
            for (TripStatus status : List.of(TripStatus.DRIVER_EN_ROUTE, TripStatus.DRIVER_ARRIVED, TripStatus.IN_PROGRESS)) {
                when(trip.getStatus()).thenReturn(status);
                assertThat(controller.access(id).getBody()).isEqualTo(new PassengerTrackingController.Access(id, driver));
            }
            for (TripStatus status : List.of(TripStatus.ASSIGNED, TripStatus.SCHEDULED, TripStatus.COMPLETED, TripStatus.CANCELLED_BY_PASSENGER, TripStatus.NO_SHOW)) {
                when(trip.getStatus()).thenReturn(status);
                assertThatThrownBy(() -> controller.access(id)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
            }
        }
        when(users.require()).thenReturn(new CurrentUser(UUID.randomUUID(), Set.of(Role.PASSENGER), Map.of(), Language.DEFAULT));
        assertThatThrownBy(() -> controller.access(id)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        when(users.require()).thenReturn(new CurrentUser(rider, Set.of(Role.DRIVER), Map.of(), Language.DEFAULT));
        assertThatThrownBy(() -> controller.access(id)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }
}
