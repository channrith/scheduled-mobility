package com.mobility.core.dispatch.assignment;

import java.time.Instant;

import com.mobility.core.dispatch.DispatchProperties.Confirmation;

/**
 * When an assigned driver must have confirmed: 60 minutes before pickup, but never sooner than 10 minutes after the
 * assignment (a late assignment is not an exception the moment it is made), and never after the pickup itself.
 */
public final class ConfirmationDeadline {

	private ConfirmationDeadline() {
	}

	public static Instant of(Instant pickupAt, Instant assignedAt, Confirmation rules) {
		Instant byLeadTime = pickupAt.minus(rules.leadTime());
		Instant byGrace = assignedAt.plus(rules.grace());
		Instant deadline = byGrace.isAfter(byLeadTime) ? byGrace : byLeadTime;
		return deadline.isAfter(pickupAt) ? pickupAt : deadline;
	}

	public static boolean missed(Instant deadline, Instant confirmedAt, Instant now) {
		return confirmedAt == null && deadline != null && !now.isBefore(deadline);
	}
}
