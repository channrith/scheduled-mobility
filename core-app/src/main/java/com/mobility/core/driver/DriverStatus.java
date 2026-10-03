package com.mobility.core.driver;

/** Driver onboarding and operating status. Only {@link #APPROVED} drivers may be dispatched. */
public enum DriverStatus {
	PENDING, DOCS_SUBMITTED, TRAINING, APPROVED, REJECTED, SUSPENDED
}
