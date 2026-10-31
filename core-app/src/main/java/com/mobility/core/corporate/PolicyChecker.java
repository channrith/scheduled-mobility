package com.mobility.core.corporate;

import java.util.List;
import java.util.UUID;

/** Used by booking to decide whether a ride may be charged to a corporate account. */
public interface PolicyChecker {

	record PolicyDecision(boolean allowed, List<PolicyViolation> violations) {

		public static PolicyDecision of(List<PolicyViolation> violations) {
			return new PolicyDecision(violations.isEmpty(), List.copyOf(violations));
		}
	}

	/**
	 * @param riderUserId the person riding (an active member of the account)
	 */
	PolicyDecision check(UUID corporateId, UUID riderUserId, RideRequest ride);
}
