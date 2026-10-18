package com.mobility.core.booking;

import static com.mobility.core.booking.BookingApi.cashRide;
import static com.mobility.core.booking.BookingApi.firstTrip;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.booking.BookingApi.Passenger;
import com.mobility.core.corporate.CorporateApi;
import com.mobility.core.corporate.CorporateApi.Company;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.shared.time.BusinessTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class BookingRulesIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	MutableClock clock;

	static BookingApi api;

	Passenger passenger;

	@BeforeEach
	void setUp() {
		if (api == null) {
			api = new BookingApi(client, issuer);
		}
		passenger = api.passenger();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static Instant inTwoHours() {
		return Instant.now().plus(Duration.ofHours(2));
	}

	Map<String, Object> ride(UUID quote, Object... keyValues) {
		Map<String, Object> body = new HashMap<>(cashRide(quote));
		for (int i = 0; i < keyValues.length; i += 2) {
			body.put((String) keyValues[i], keyValues[i + 1]);
		}
		return body;
	}

	@Test
	void quoteMustBeYoursUnexpiredAndPaidAProperWay() {
		UUID quote = api.quote(passenger.token(), inTwoHours(), null, false);

		api.book(api.passenger().token(), cashRide(quote)).expectStatus().isNotFound().expectBody()
			.jsonPath("$.code").isEqualTo("booking.quote-not-found");
		api.book(passenger.token(), ride(quote, "paymentMethod", "KHQR")).expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("booking.payment-method-unavailable");
		api.book(passenger.token(), ride(quote, "paymentMethod", "CORPORATE_INVOICE")).expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("booking.payment-method-invalid");
		api.book(passenger.token(), ride(quote, "type", "ON_DEMAND")).expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("booking.type-not-available");
		api.book(passenger.token(), ride(quote, "pickupVoiceNoteKey", "../etc/passwd")).expectStatus().isBadRequest().expectBody()
			.jsonPath("$.field").isEqualTo("pickupVoiceNoteKey");

		clock.advance(Duration.ofMinutes(16));
		Passenger later = new Passenger(passenger.userId(),
				com.mobility.core.identity.token.SignedTokens.forUser(issuer, passenger.userId(), Role.PASSENGER));
		api.book(later.token(), cashRide(quote)).expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("booking.quote-expired")
			.jsonPath("$.detail").isEqualTo("This quote has expired. Get a new quote to book.");
	}

	@Test
	void corporateRidesFollowTheMembersPolicyAndCostCentre() {
		CorporateApi corporate = new CorporateApi(client, issuer);
		Company company = corporate.company();
		JsonNode center = corporate.addCostCenter(company, "OPS");
		JsonNode motoOnly = corporate.addMember(company, "RIDER", Map.of("policy", Map.of("allowedVehicleClasses", List.of("MOTO"))));
		JsonNode anyCar = corporate.addMember(company, "RIDER", Map.of());
		UUID quote = api.quote(company.adminToken(), inTwoHours(), company.id(), false);

		api.book(company.adminToken(), ride(quote, "paymentMethod", "CASH", "riderMemberId", anyCar.get("id").asString()))
			.expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("booking.payment-method-invalid");
		api.book(company.adminToken(), ride(quote, "paymentMethod", "CORPORATE_INVOICE", "riderMemberId",
				motoOnly.get("id").asString()))
			.expectStatus().isEqualTo(422).expectBody()
			.jsonPath("$.code").isEqualTo("booking.policy-refused")
			.jsonPath("$.refused[0].violations[0]").isEqualTo("VEHICLE_CLASS_NOT_ALLOWED");
		api.book(company.adminToken(), ride(quote, "paymentMethod", "CORPORATE_INVOICE", "riderMemberId",
				UUID.randomUUID().toString()))
			.expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("booking.rider-invalid");
		api.book(passenger.token(), ride(quote, "paymentMethod", "CORPORATE_INVOICE")).expectStatus().isNotFound();

		JsonNode booked = api.bookOk(company.adminToken(), ride(quote, "paymentMethod", "CORPORATE_INVOICE", "riderMemberId",
				anyCar.get("id").asString(), "costCenterId", center.get("id").asString()));
		assertThat(booked.get("corporateId").asString()).isEqualTo(company.id().toString());
		assertThat(booked.get("riderUserId").asString()).isEqualTo(anyCar.get("userId").asString());
		assertThat(booked.get("costCenterId").asString()).isEqualTo(center.get("id").asString());
		assertThat(booked.get("trips").get(0).get("history").get(0).get("actorRole").asString()).isEqualTo("BOOKER");
		// Another company's admin cannot see it; staff can.
		api.get("/api/v1/rides/" + booked.get("id").asString(), corporate.company().adminToken()).expectStatus().isNotFound();
		api.get("/api/v1/rides/" + booked.get("id").asString(), api.tokenFor(Role.SUPPORT)).expectStatus().isOk();
	}

	@Test
	void airportBookingsNeedAFlightAndTheAirportZone() {
		UUID cityQuote = api.quote(passenger.token(), inTwoHours(), null, false);
		UUID airportQuote = api.quote(passenger.token(), inTwoHours(), null, true);

		api.book(passenger.token(), ride(airportQuote, "type", "AIRPORT")).expectStatus().isBadRequest().expectBody()
			.jsonPath("$.field").isEqualTo("flightNo");
		api.book(passenger.token(), ride(airportQuote, "type", "AIRPORT", "flightNo", "not a flight")).expectStatus().isBadRequest();
		api.book(passenger.token(), ride(cityQuote, "type", "AIRPORT", "flightNo", "K6 123")).expectStatus().isEqualTo(422)
			.expectBody().jsonPath("$.code").isEqualTo("booking.not-an-airport-trip");

		JsonNode booked = api.bookOk(passenger.token(), ride(airportQuote, "type", "AIRPORT", "flightNo", "k6  123"));
		assertThat(booked.get("type").asString()).isEqualTo("AIRPORT");
		assertThat(booked.get("flightNo").asString()).isEqualTo("K6 123");
	}

	@Test
	void recurringSeriesGetsOneTripPerDateAndCanBeCancelledPartly() {
		LocalDate monday = LocalDate.now(BusinessTime.ZONE).plusDays(2).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
		Instant firstPickup = monday.atTime(7, 30).atZone(BusinessTime.ZONE).toInstant();
		UUID quote = api.quote(passenger.token(), firstPickup, null, false);

		JsonNode series = api.bookOk(passenger.token(), ride(quote, "type", "RECURRING", "recurrence",
				Map.of("days", List.of("MONDAY", "WEDNESDAY", "FRIDAY"), "until", monday.plusDays(11).toString())));

		JsonNode trips = series.get("trips");
		assertThat(trips).hasSize(6);
		assertThat(trips.valueStream().map(t -> t.get("occurrence").asInt())).containsExactly(1, 2, 3, 4, 5, 6);
		assertThat(trips.get(0).get("pickupAt").asString()).isEqualTo(firstPickup.toString());
		assertThat(series.get("recurrence").get("days").valueStream().map(JsonNode::asString))
			.containsExactlyInAnyOrder("MONDAY", "WEDNESDAY", "FRIDAY");
		String rideId = series.get("id").asString();

		JsonNode afterOne = api.json(api.post("/api/v1/rides/" + rideId + "/cancel", passenger.token(),
				Map.of("tripId", trips.get(2).get("id").asString(), "reason", "Public holiday"), null));
		assertThat(afterOne.get("trips").valueStream().map(t -> t.get("status").asString())).containsExactly("SCHEDULED",
				"SCHEDULED", "CANCELLED_BY_PASSENGER", "SCHEDULED", "SCHEDULED", "SCHEDULED");

		JsonNode afterAll = api.json(api.post("/api/v1/rides/" + rideId + "/cancel", passenger.token(), Map.of(), null));
		assertThat(afterAll.get("trips").valueStream().map(t -> t.get("status").asString()))
			.allMatch(s -> s.equals("CANCELLED_BY_PASSENGER"));
		api.post("/api/v1/rides/" + rideId + "/cancel", passenger.token(), Map.of(), null).expectStatus().isEqualTo(409)
			.expectBody().jsonPath("$.code").isEqualTo("booking.nothing-to-cancel");

		api.book(passenger.token(), ride(api.quote(passenger.token(), firstPickup, null, false), "type", "RECURRING", "recurrence",
				Map.of("days", List.of("TUESDAY"), "until", monday.plusDays(7).toString())))
			.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("booking.recurrence-invalid");
	}

	@Test
	void everyoneSeesOnlyTheirOwnRidesAndTrips() {
		DriverApi drivers = new DriverApi(client, issuer);
		Registered driver = drivers.approvedDriver();
		Registered otherDriver = drivers.approvedDriver();
		JsonNode booked = api.bookOk(passenger.token(), cashRide(api.quote(passenger.token(), inTwoHours(), null, false)));
		UUID tripId = firstTrip(booked);
		String rideId = booked.get("id").asString();

		api.get("/api/v1/rides/" + rideId, api.passenger().token()).expectStatus().isNotFound();
		JsonNode upcoming = api.json(api.get("/api/v1/rides", passenger.token()));
		assertThat(upcoming.get("content").valueStream().map(t -> t.get("trip").get("id").asString())).contains(tripId.toString());
		assertThat(api.json(api.get("/api/v1/rides", api.passenger().token())).get("content")).isEmpty();

		api.json(api.assign(tripId, driver.driverId(), 0));
		api.driver(otherDriver.token(), tripId, "en-route").expectStatus().isNotFound();
		JsonNode mine = api.json(api.get("/api/v1/driver/trips", driver.token()));
		assertThat(mine.valueStream().map(t -> t.get("trip").get("id").asString())).contains(tripId.toString());
		assertThat(mine.get(0).get("pickupNotes").asString()).isEqualTo("Blue gate behind the pharmacy");
		assertThat(api.json(api.get("/api/v1/driver/trips", otherDriver.token()))).isEmpty();

		api.get("/api/v1/driver/trips", passenger.token()).expectStatus().isForbidden();
		api.post("/api/v1/ops/trips/" + tripId + "/assign", passenger.token(), Map.of("driverId", driver.driverId().toString(),
				"version", 1), null).expectStatus().isForbidden();
		JsonNode ops = api.json(api.get("/api/v1/ops/trips?status=ASSIGNED", api.tokenFor(Role.DISPATCHER)));
		assertThat(ops.get("content").valueStream().map(t -> t.get("trip").get("id").asString())).contains(tripId.toString());
		JsonNode detail = api.json(api.get("/api/v1/ops/trips/" + tripId, api.tokenFor(Role.DISPATCHER)));
		assertThat(detail.get("history")).hasSize(2);
	}

	@Test
	void opsCanUnassignCancelAndHandleDisputes() {
		DriverApi drivers = new DriverApi(client, issuer);
		Registered driver = drivers.approvedDriver();
		String dispatcher = api.tokenFor(Role.DISPATCHER);
		UUID tripId = firstTrip(api.bookOk(passenger.token(), cashRide(api.quote(passenger.token(), inTwoHours(), null, false))));

		JsonNode assigned = api.json(api.assign(tripId, driver.driverId(), 0));
		api.post("/api/v1/ops/trips/" + tripId + "/unassign", dispatcher, Map.of("version", assigned.get("version").asLong(),
				"reason", "short"), null).expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("booking.reason-required");
		JsonNode back = api.json(api.post("/api/v1/ops/trips/" + tripId + "/unassign", dispatcher,
				Map.of("version", assigned.get("version").asLong(), "reason", "Driver's car broke down"), null));
		assertThat(back.get("status").asString()).isEqualTo("SCHEDULED");
		assertThat(back.get("driverId").isNull()).isTrue();

		JsonNode again = api.json(api.assign(tripId, driver.driverId(), back.get("version").asLong()));
		for (String step : List.of("en-route", "arrived", "start", "complete")) {
			api.json(api.driver(driver.token(), tripId, step));
		}
		assertThat(again.get("status").asString()).isEqualTo("ASSIGNED");
		api.json(api.post("/api/v1/ops/trips/" + tripId + "/dispute", dispatcher, Map.of("reason", "Rider disputes the route taken"), null));
		JsonNode resolved = api.json(api.post("/api/v1/ops/trips/" + tripId + "/resolve", dispatcher,
				Map.of("reason", "Route checked: it was correct"), null));
		assertThat(resolved.get("status").asString()).isEqualTo("COMPLETED");

		UUID other = firstTrip(api.bookOk(passenger.token(), cashRide(api.quote(passenger.token(), inTwoHours(), null, false))));
		JsonNode cancelled = api.json(api.post("/api/v1/ops/trips/" + other + "/cancel", dispatcher,
				Map.of("reason", "No car of this class available"), null));
		assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED_BY_OPS");
	}

	@Test
	void cashThatDiffersFromTheFareIsFlagged() {
		DriverApi drivers = new DriverApi(client, issuer);
		Registered driver = drivers.approvedDriver();
		JsonNode booked = api.bookOk(passenger.token(), cashRide(api.quote(passenger.token(), inTwoHours(), null, false)));
		UUID tripId = firstTrip(booked);
		api.json(api.assign(tripId, driver.driverId(), 0));
		for (String step : List.of("en-route", "arrived", "start", "complete")) {
			api.json(api.driver(driver.token(), tripId, step));
		}
		long riel = booked.get("equivalentMinor").asLong();

		JsonNode inRiel = api.json(api.post("/api/v1/driver/trips/" + tripId + "/cash", driver.token(),
				Map.of("amountMinor", riel, "currency", "KHR"), null));
		assertThat(inRiel.get("cash").get("mismatch").asBoolean()).isFalse();
		api.post("/api/v1/driver/trips/" + tripId + "/cash", driver.token(), Map.of("amountMinor", riel, "currency", "KHR"), null)
			.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("booking.cash-already-reported");

		UUID second = firstTrip(api.bookOk(passenger.token(), cashRide(api.quote(passenger.token(), inTwoHours(), null, false))));
		api.json(api.assign(second, driver.driverId(), 0));
		for (String step : List.of("en-route", "arrived", "start", "complete")) {
			api.json(api.driver(driver.token(), second, step));
		}
		JsonNode short_ = api.json(api.post("/api/v1/driver/trips/" + second + "/cash", driver.token(),
				Map.of("amountMinor", 100, "currency", "USD"), null));
		assertThat(short_.get("cash").get("mismatch").asBoolean()).isTrue();
	}
}
