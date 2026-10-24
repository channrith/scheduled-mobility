package com.mobility.core.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.booking.BookingApi;
import com.mobility.core.booking.BookingApi.Passenger;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.identity.token.SignedTokens;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Bookings to dispatch, and the dispatch endpoints. */
public class DispatchApi {

	public final BookingApi booking;

	public final AccessTokenIssuer issuer;

	public final UUID dispatcherId = UUID.randomUUID();

	public DispatchApi(RestTestClient client, AccessTokenIssuer issuer) {
		this.booking = new BookingApi(client, issuer);
		this.issuer = issuer;
	}

	/** Fresh: tokens issued before the test clock moved are expired. */
	public String dispatcher() {
		return SignedTokens.forUser(issuer, dispatcherId, Role.DISPATCHER);
	}

	public String driverToken(UUID userId) {
		return SignedTokens.forUser(issuer, userId, Role.DRIVER);
	}

	/** A booked CAR trip picking up at {@code pickupAt}; returns the trip id. */
	public UUID trip(Instant pickupAt, boolean toAirport) {
		Passenger p = booking.passenger();
		UUID quote = booking.quote(p.token(), pickupAt, null, toAirport);
		Map<String, Object> body = BookingApi.cashRide(quote);
		if (toAirport) {
			body.put("type", "AIRPORT");
			body.put("flightNo", "K6 101");
		}
		return BookingApi.firstTrip(booking.bookOk(p.token(), body));
	}

	public UUID trip(Duration fromNow) {
		return trip(Instant.now().plus(fromNow), false);
	}

	public JsonNode detail(UUID tripId) {
		return booking.json(booking.get("/api/v1/dispatch/trips/" + tripId, dispatcher()));
	}

	public long tripVersion(UUID tripId) {
		return detail(tripId).get("trip").get("tripVersion").asLong();
	}

	public long assignmentVersion(UUID tripId) {
		return detail(tripId).get("trip").get("assignment").get("version").asLong();
	}

	public RestTestClient.ResponseSpec assign(UUID tripId, UUID driverId, UUID backupId, String reason, String note) {
		Map<String, Object> body = new HashMap<>(Map.of("driverId", driverId.toString(), "reasonCode", reason, "tripVersion",
				tripVersion(tripId)));
		if (backupId != null) {
			body.put("backupDriverId", backupId.toString());
		}
		if (note != null) {
			body.put("note", note);
		}
		return booking.post("/api/v1/dispatch/trips/" + tripId + "/assign", dispatcher(), body, null);
	}

	public JsonNode assignOk(UUID tripId, UUID driverId, UUID backupId) {
		return booking.json(assign(tripId, driverId, backupId, "PLANNED", null));
	}

	public RestTestClient.ResponseSpec switchToBackup(UUID tripId, long assignmentVersion) {
		return booking.post("/api/v1/dispatch/trips/" + tripId + "/switch-to-backup", dispatcher(),
				Map.of("assignmentVersion", assignmentVersion), null);
	}

	public JsonNode exceptionsFor(UUID tripId) {
		return booking.json(booking.get("/api/v1/dispatch/exceptions?status=OPEN,ACKNOWLEDGED,RESOLVED,AUTO_RESOLVED", dispatcher()));
	}

	public JsonNode openException(UUID tripId, String type) {
		for (JsonNode e : booking.json(booking.get("/api/v1/dispatch/exceptions", dispatcher()))) {
			if (e.get("tripId").asString().equals(tripId.toString()) && e.get("type").asString().equals(type)) {
				return e;
			}
		}
		return null;
	}

	public JsonNode shift(String driverToken, Instant from, Instant to) {
		return booking.post("/api/v1/driver/shifts", driverToken, Map.of("startsAt", from.toString(), "endsAt", to.toString()), null)
			.expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
	}
}
