package com.mobility.core.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.mobility.core.IntegrationTest;
import com.mobility.core.dispatch.DispatchApi;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.identity.token.AccessTokenIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PassengerTrackingIT {
    @Autowired RestTestClient client;
    @Autowired AccessTokenIssuer issuer;

    private DispatchApi dispatch;
    private BookingApi booking;
    private DriverApi drivers;

    @BeforeEach
    void setup() {
        dispatch = new DispatchApi(client, issuer);
        booking = dispatch.booking;
        drivers = new DriverApi(client, issuer);
    }

    @Test
    void trackingRequiresOwnershipAndAnActiveJourneyAndEndsOnCompletion() {
        var passenger = booking.passenger();
        var stranger = booking.passenger();
        var driver = drivers.approvedDriver();
        UUID tripId = BookingApi.firstTrip(book(passenger));
        String path = trackingPath(tripId);

        booking.get(path, passenger.token()).expectStatus().isEqualTo(409);
        dispatch.assignOk(tripId, driver.driverId(), null);
        booking.driver(driver.token(), tripId, "confirm").expectStatus().isOk();
        booking.get(path, passenger.token()).expectStatus().isEqualTo(409);

        for (String step : List.of("en-route", "arrived", "start")) {
            booking.driver(driver.token(), tripId, step).expectStatus().isOk();
            JsonNode access = booking.get(path, passenger.token())
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(access.get("tripId").asString()).isEqualTo(tripId.toString());
            assertThat(access.get("driverId").asString()).isEqualTo(driver.driverId().toString());
            booking.get(path, stranger.token()).expectStatus().isNotFound();
            booking.get(path, driver.token()).expectStatus().isForbidden();
        }

        booking.driver(driver.token(), tripId, "complete").expectStatus().isOk();
        booking.get(path, passenger.token()).expectStatus().isEqualTo(409);
    }

    @Test
    void cancellingAnEnRouteTripRevokesTrackingAccess() {
        var passenger = booking.passenger();
        var driver = drivers.approvedDriver();
        JsonNode ride = book(passenger);
        UUID tripId = BookingApi.firstTrip(ride);
        dispatch.assignOk(tripId, driver.driverId(), null);
        booking.driver(driver.token(), tripId, "confirm").expectStatus().isOk();
        booking.driver(driver.token(), tripId, "en-route").expectStatus().isOk();
        booking.get(trackingPath(tripId), passenger.token()).expectStatus().isOk();

        booking.post("/api/v1/rides/" + ride.get("id").asString() + "/cancel",
                passenger.token(), Map.of(), null).expectStatus().isOk();

        booking.get(trackingPath(tripId), passenger.token()).expectStatus().isEqualTo(409);
    }

    private JsonNode book(BookingApi.Passenger passenger) {
        UUID quote = booking.quote(passenger.token(), Instant.now().plus(Duration.ofDays(2)), null, false);
        return booking.bookOk(passenger.token(), BookingApi.cashRide(quote));
    }

    private String trackingPath(UUID tripId) {
        return "/api/v1/passenger/trips/" + tripId + "/tracking-access";
    }
}
