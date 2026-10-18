package com.mobility.core.booking.trip;

import static com.mobility.core.booking.TripStatus.ASSIGNED;
import static com.mobility.core.booking.TripStatus.CANCELLED_BY_DRIVER;
import static com.mobility.core.booking.TripStatus.CANCELLED_BY_OPS;
import static com.mobility.core.booking.TripStatus.CANCELLED_BY_PASSENGER;
import static com.mobility.core.booking.TripStatus.COMPLETED;
import static com.mobility.core.booking.TripStatus.DISPUTED;
import static com.mobility.core.booking.TripStatus.DRIVER_ARRIVED;
import static com.mobility.core.booking.TripStatus.DRIVER_EN_ROUTE;
import static com.mobility.core.booking.TripStatus.IN_PROGRESS;
import static com.mobility.core.booking.TripStatus.NO_SHOW;
import static com.mobility.core.booking.TripStatus.SCHEDULED;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Which status may follow which. Who may make a change (driver, rider, ops) is checked by the service; this is
 * only the order of things. A driver dropping a trip sends it back to SCHEDULED so ops can reassign it.
 */
public final class TripStateMachine {

	static final Map<TripStatus, Set<TripStatus>> NEXT = new EnumMap<>(TripStatus.class);

	static {
		NEXT.put(SCHEDULED, EnumSet.of(ASSIGNED, CANCELLED_BY_PASSENGER, CANCELLED_BY_OPS));
		NEXT.put(ASSIGNED, EnumSet.of(DRIVER_EN_ROUTE, SCHEDULED, CANCELLED_BY_PASSENGER, CANCELLED_BY_OPS, CANCELLED_BY_DRIVER));
		NEXT.put(DRIVER_EN_ROUTE, EnumSet.of(DRIVER_ARRIVED, SCHEDULED, CANCELLED_BY_PASSENGER, CANCELLED_BY_OPS, CANCELLED_BY_DRIVER));
		NEXT.put(DRIVER_ARRIVED, EnumSet.of(IN_PROGRESS, NO_SHOW, CANCELLED_BY_OPS));
		NEXT.put(IN_PROGRESS, EnumSet.of(COMPLETED, CANCELLED_BY_OPS));
		NEXT.put(COMPLETED, EnumSet.of(DISPUTED));
		NEXT.put(DISPUTED, EnumSet.of(COMPLETED));
		for (TripStatus s : TripStatus.values()) {
			NEXT.putIfAbsent(s, EnumSet.noneOf(TripStatus.class));
		}
	}

	private TripStateMachine() {
	}

	public static boolean allowed(TripStatus from, TripStatus to) {
		return NEXT.get(from).contains(to);
	}

	/** @throws ApiException 409 {@code booking.invalid-transition} */
	public static void check(TripStatus from, TripStatus to) {
		if (!allowed(from, to)) {
			throw new ApiException(HttpStatus.CONFLICT, "booking.invalid-transition", from, to).withProperty("from", from.name())
				.withProperty("to", to.name());
		}
	}
}
