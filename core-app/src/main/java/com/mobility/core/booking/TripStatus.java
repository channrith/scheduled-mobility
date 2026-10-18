package com.mobility.core.booking;

import java.util.EnumSet;
import java.util.Set;

public enum TripStatus {
	SCHEDULED, ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED_BY_PASSENGER,
	CANCELLED_BY_DRIVER, CANCELLED_BY_OPS, NO_SHOW, DISPUTED;

	/** Still to happen or happening. */
	public static final Set<TripStatus> OPEN = EnumSet.of(SCHEDULED, ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, IN_PROGRESS);

	public static final Set<TripStatus> CANCELLED = EnumSet.of(CANCELLED_BY_PASSENGER, CANCELLED_BY_DRIVER, CANCELLED_BY_OPS);

	public boolean isOpen() {
		return OPEN.contains(this);
	}
}
