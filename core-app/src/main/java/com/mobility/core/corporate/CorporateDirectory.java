package com.mobility.core.corporate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Read-only account lookups for other modules (pricing: overrides and the service fee shown with quotes). */
public interface CorporateDirectory {

	/** @param serviceFeePct added to invoiced rides, in percent */
	record CorporateSummary(UUID id, String legalName, String displayName, AccountStatus status, String currency,
			BigDecimal serviceFeePct) {
	}

	Optional<CorporateSummary> find(UUID corporateId);
}
