package com.mobility.core.driver.vehicle;

/** Who owns the vehicle. THIRD_PARTY covers rented tuk-tuks: owner name and phone are then required. */
public enum VehicleOwnership {
	DRIVER, THIRD_PARTY, COMPANY
}
