package com.mobility.core.pricing.settings;

/** Values admins change without a new rule version. */
public enum Setting {
	/** Fuel price relative to the baseline the rules were set at (1.000 = baseline). */
	FUEL_INDEX,
	/** Riel per US dollar, for KHR equivalents and currency conversion. */
	USD_KHR_RATE
}
