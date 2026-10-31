package com.mobility.core.pricing.zone;

public enum ZoneType {
	/** Service area: one end of every ride must be in a CITY (or AIRPORT) zone. */
	CITY,
	/** Adds its fixed surcharge once per ride starting or ending inside it. */
	AIRPORT,
	/** No pickups or drop-offs inside. */
	RESTRICTED
}
