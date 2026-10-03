package com.mobility.core.driver.profile;

import java.time.Instant;

import com.mobility.core.driver.DriverStatus;

/** Result of a successful transition; persisted as a status-history row and audited. */
public record StatusChange(DriverStatus from, DriverStatus to, String reason, Instant noticeAt) {
}
