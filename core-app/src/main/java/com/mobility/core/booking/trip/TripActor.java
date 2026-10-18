package com.mobility.core.booking.trip;

/** Who made a status change (trip_events.actor_role). */
public enum TripActor {
	/** The person riding. */
	RIDER,
	/** Booked it for someone else (a corporate booker or admin). */
	BOOKER,
	DRIVER,
	/** Dispatcher or admin. */
	OPS,
	SYSTEM
}
