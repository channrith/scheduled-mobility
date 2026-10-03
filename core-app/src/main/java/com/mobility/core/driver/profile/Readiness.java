package com.mobility.core.driver.profile;

import java.util.Set;
import java.util.TreeSet;

import com.mobility.core.driver.document.DocumentType;

/**
 * Snapshot of what a driver has, used by status-transition guards.
 *
 * @param missing required documents not uploaded (or rejected and not re-uploaded)
 * @param pendingReview required documents uploaded but not yet approved
 * @param expired required approved documents past their expiry date
 * @param hasActiveVehicle whether a vehicle is currently assigned
 */
public record Readiness(Set<DocumentType> missing, Set<DocumentType> pendingReview, Set<DocumentType> expired,
		boolean hasActiveVehicle) {

	public Readiness {
		missing = Set.copyOf(missing);
		pendingReview = Set.copyOf(pendingReview);
		expired = Set.copyOf(expired);
	}

	public boolean allUploaded() {
		return missing.isEmpty();
	}

	public boolean allApprovedAndValid() {
		return missing.isEmpty() && pendingReview.isEmpty() && expired.isEmpty();
	}

	static java.util.List<String> names(Set<DocumentType> types) {
		return new TreeSet<>(types.stream().map(Enum::name).toList()).stream().toList();
	}
}
