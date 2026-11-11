package com.mobility.core.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.identity.token.AccessTokenIssuer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@IntegrationTest
class BookingModesIT {
    @Autowired RestTestClient client;
    @Autowired AccessTokenIssuer issuer;
    @Autowired MutableClock clock;
    @Autowired com.mobility.core.AuthTestClient auth;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    static BookingApi api;
    BookingApi.Passenger passenger;

    @BeforeEach
    void setup() {
        if (api == null) api = new BookingApi(client, issuer);
        passenger = api.passenger();
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    Map<String, Object> meter(boolean airport) {
        var body = new HashMap<String, Object>();
        body.put("type", airport ? "AIRPORT" : "ON_DEMAND");
        body.put("fareModel", "METERED");
        body.put("paymentMethod", "CASH");
        body.put("vehicleClass", "CAR");
        body.put(
                "pickup",
                Map.of("latitude", airport ? api.airportLat : api.lat, "longitude", api.lng));
        if (airport) {
            body.put("pickupAt", clock.instant().plusSeconds(3600).toString());
            body.put("flightNo", "K6101");
            body.put("meetingPoint", "Terminal arrivals gate 2");
        }
        return body;
    }

    @Test
    void destinationFreeBookingsHaveNoFakeQuoteDestinationOrFareAndRetryExactly() {
        for (boolean airport : new boolean[] {false, true}) {
            var body = meter(airport);
            var key = UUID.randomUUID().toString();
            var first =
                    api.post("/api/v1/rides", passenger.token(), body, key)
                            .expectStatus()
                            .isCreated()
                            .expectBody(JsonNode.class)
                            .returnResult()
                            .getResponseBody();
            var retry =
                    api.post("/api/v1/rides", passenger.token(), body, key)
                            .expectStatus()
                            .isCreated()
                            .expectBody(JsonNode.class)
                            .returnResult()
                            .getResponseBody();
            assertThat(first.get("id")).isEqualTo(retry.get("id"));
            assertThat(first.get("fareModel").asString()).isEqualTo("METERED");
            assertThat(first.get("type").asString()).isEqualTo(airport ? "AIRPORT" : "ON_DEMAND");
            assertThat(first.hasNonNull("quoteId")).isFalse();
            assertThat(first.hasNonNull("dropoff")).isFalse();
            assertThat(first.hasNonNull("fareMinor")).isFalse();
            assertThat(first.get("fareStatus").asString()).isEqualTo("PENDING");
        }
        var invalid = meter(false);
        invalid.put("pickupAt", clock.instant().toString());
        api.book(passenger.token(), invalid).expectStatus().isBadRequest();
        invalid = meter(true);
        invalid.remove("meetingPoint");
        api.book(passenger.token(), invalid).expectStatus().isBadRequest();
        invalid = meter(true);
        invalid.put("pickupAt", clock.instant().minusSeconds(1).toString());
        api.book(passenger.token(), invalid).expectStatus().isBadRequest();
    }

    @Test
    void rejectsIncompatibleFarePairsAndNeverInfersMeteredFromMissingDestination() {
        for (String type : new String[] {"SCHEDULED", "RECURRING"}) {
            var body = meter(false);
            body.put("type", type);
            api.book(passenger.token(), body)
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("booking.invalid-combination");
        }
        var implicit = meter(false);
        implicit.remove("fareModel");
        api.book(passenger.token(), implicit)
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("booking.invalid-combination");
        var quoted =
                BookingApi.cashRide(
                        api.quote(
                                passenger.token(), clock.instant().plusSeconds(3600), null, false));
        quoted.put("fareModel", "QUOTED");
        var ride = api.bookOk(passenger.token(), quoted);
        assertThat(ride.get("fareModel").asString()).isEqualTo("QUOTED");
        var route =
                Map.of(
                        "pickup",
                        Map.of("latitude", api.lat, "longitude", api.lng),
                        "dropoff",
                        Map.of("latitude", api.lat + .02, "longitude", api.lng),
                        "vehicleClass",
                        "CAR",
                        "fareModel",
                        "METERED");
        api.post("/api/v1/quotes", passenger.token(), route, UUID.randomUUID().toString())
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("pricing.quote-not-eligible");
    }

    @Test
    void rejectsFakeDestinationAndIncompleteAirportPickup() {
        var body = meter(false);
        body.put("dropoff", Map.of("latitude", api.lat, "longitude", api.lng));
        api.book(passenger.token(), body).expectStatus().isBadRequest();
        body = meter(true);
        body.put("pickup", Map.of("latitude", api.lat, "longitude", api.lng));
        api.book(passenger.token(), body).expectStatus().isEqualTo(422);
        var quote = api.quote(passenger.token(), clock.instant().plusSeconds(3600), null, true);
        var fixed = BookingApi.cashRide(quote);
        fixed.put("contractVersion", 2);
        fixed.put("type", "AIRPORT");
        fixed.put("flightNo", "K6101");
        api.book(passenger.token(), fixed).expectStatus().isBadRequest();
        fixed.put("meetingPoint", "Arrivals gate 2");
        api.book(passenger.token(), fixed).expectStatus().isCreated();
    }

    @Test
    void rejectsBadReadingsAndRefusesToGuessAcrossAnOutage() {
        var driver = new DriverApi(client, issuer).approvedDriver();
        var ride = api.bookOk(passenger.token(), meter(false));
        var trip = BookingApi.firstTrip(ride);
        api.assign(trip, driver.driverId(), 0).expectStatus().isOk();
        api.driver(driver.token(), trip, "en-route").expectStatus().isOk();
        api.driver(driver.token(), trip, "arrived").expectStatus().isOk();
        api.driver(driver.token(), trip, "start").expectStatus().isOk();
        sample(passenger.token(), api.lat, api.lng).expectStatus().isForbidden();
        sample(driver.token(), api.lat, api.lng).expectStatus().isNoContent();
        clock.advance(Duration.ofSeconds(2));
        sample(driver.token(), api.lat + 1, api.lng).expectStatus().isEqualTo(409);
        clock.advance(Duration.ofSeconds(121));
        sample(driver.token(), api.lat + .001, api.lng).expectStatus().isEqualTo(409);
        api.driver(driver.token(), trip, "complete").expectStatus().isEqualTo(409);
        var pending =
                api.get("/api/v1/rides/" + ride.get("id").asString(), passenger.token())
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(pending.hasNonNull("fareMinor")).isFalse();
    }

    @Test
    void rideNowUsesServerQuoteAndCurrentTime() {
        var q =
                api.post(
                                "/api/v1/quotes",
                                passenger.token(),
                                Map.of(
                                        "pickup",
                                        Map.of("latitude", api.lat, "longitude", api.lng),
                                        "dropoff",
                                        Map.of("latitude", api.lat + .02, "longitude", api.lng),
                                        "vehicleClass",
                                        "CAR"),
                                UUID.randomUUID().toString())
                        .expectStatus()
                        .isCreated()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        var body = BookingApi.cashRide(UUID.fromString(q.get("id").asString()));
        body.put("type", "ON_DEMAND");
        clock.advance(
                Duration.ofMinutes(
                        6)); // a still-valid NOW quote does not become a scheduled quote as time
                             // passes
        var ride = api.bookOk(passenger.token(), body);
        assertThat(ride.get("fareMinor")).isEqualTo(q.get("totalMinor"));
        assertThat(ride.get("fareStatus").asString()).isEqualTo("FINAL");
    }

    @Test
    void backendMeterFinalizesOnlyWithDriverOwnedContinuousSamples() {
        var driver = new DriverApi(client, issuer).approvedDriver();
        var ride = api.bookOk(passenger.token(), meter(false));
        var trip = BookingApi.firstTrip(ride);
        api.assign(trip, driver.driverId(), 0).expectStatus().isOk();
        api.driver(driver.token(), trip, "en-route").expectStatus().isOk();
        api.driver(driver.token(), trip, "arrived").expectStatus().isOk();
        api.driver(driver.token(), trip, "start").expectStatus().isOk();
        api.driver(driver.token(), trip, "complete").expectStatus().isEqualTo(409);
        sample(driver.token(), api.lat, api.lng).expectStatus().isNoContent();
        clock.advance(Duration.ofSeconds(20));
        sample(driver.token(), api.lat + .001, api.lng).expectStatus().isNoContent();
        api.driver(driver.token(), trip, "complete").expectStatus().isOk();
        var finished =
                api.get("/api/v1/rides/" + ride.get("id").asString(), passenger.token())
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(finished.get("fareStatus").asString()).isEqualTo("FINAL");
        assertThat(finished.get("fareMinor").asLong()).isPositive();
        api.driver(driver.token(), trip, "complete").expectStatus().isEqualTo(409);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "booking.preview.seconds",
            matches = "[1-9][0-9]*")
    void simulatorPreview() throws Exception {
        var tokens = auth.login("+855971111111");
        for (var entry :
                Map.of("HOME", api.lat, "OFFICE", api.lat + .02, "CUSTOM", api.airportLat)
                        .entrySet()) {
            var body = new HashMap<String, Object>();
            body.put("label", entry.getKey());
            body.put("latitude", entry.getValue());
            body.put("longitude", api.lng);
            if (entry.getKey().equals("CUSTOM")) body.put("customLabel", "QA airport arrivals");
            body.put("note", "Synthetic simulator fixture");
            api.post(
                            "/api/v1/me/saved-places",
                            tokens.accessToken(),
                            body,
                            UUID.randomUUID().toString())
                    .expectStatus()
                    .isCreated();
        }
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var path = java.nio.file.Path.of("/tmp/mobility-booking-preview.json");
        java.nio.file.Files.deleteIfExists(path);
        java.nio.file.Files.createFile(
                path,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        java.nio.file.Files.writeString(
                path,
                mapper.writeValueAsString(
                        Map.of("origin", "http://127.0.0.1:" + port, "tokens", tokens)));
        try {
            long seconds = Long.parseLong(System.getProperty("booking.preview.seconds"));
            for (long i = 0; i < seconds && java.nio.file.Files.exists(path); i++)
                Thread.sleep(1000);
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
    }

    RestTestClient.ResponseSpec sample(String token, double lat, double lng) {
        return api.post(
                "/api/v1/driver/meter/location",
                token,
                Map.of(
                        "latitude",
                        lat,
                        "longitude",
                        lng,
                        "accuracyMeters",
                        5,
                        "recordedAt",
                        clock.instant().toString()),
                UUID.randomUUID().toString());
    }
}
