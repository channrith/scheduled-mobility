package com.mobility.core.booking;

public enum BookingType {
	/** One ride at a set time. */
	SCHEDULED,
	/** To or from the airport, with a flight number. */
	AIRPORT,
	/** The same ride on chosen weekdays until an end date: one trip per date. */
	RECURRING,
	/** Ride now; in the model, not offered yet. */
	ON_DEMAND
}
