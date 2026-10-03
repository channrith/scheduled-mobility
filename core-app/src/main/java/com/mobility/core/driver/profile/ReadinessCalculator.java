package com.mobility.core.driver.profile;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentStatus;
import com.mobility.core.driver.document.DocumentType;
import com.mobility.core.driver.document.DriverDocument;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.driver.vehicle.VehicleView;
import com.mobility.core.shared.time.BusinessTime;
import org.springframework.stereotype.Component;

/** Works out which required documents a driver is missing, awaiting review or has expired. */
@Component
class ReadinessCalculator {

	private final DocumentService documents;

	private final VehicleService vehicles;

	private final Clock clock;

	ReadinessCalculator(DocumentService documents, VehicleService vehicles, Clock clock) {
		this.documents = documents;
		this.vehicles = vehicles;
		this.clock = clock;
	}

	/** Personal documents only (submission and training). */
	Readiness forOnboarding(UUID driverId) {
		return compute(driverId, false);
	}

	/** Personal documents plus the assigned vehicle's documents (approval and reinstatement). */
	Readiness forDispatch(UUID driverId) {
		return compute(driverId, true);
	}

	private Readiness compute(UUID driverId, boolean includeVehicle) {
		List<DriverDocument> current = documents.currentDocuments(driverId);
		Optional<UUID> vehicleId = vehicles.activeVehicle(driverId).map(VehicleView::id);
		LocalDate today = BusinessTime.today(clock);

		Set<DocumentType> required = EnumSet.copyOf(DocumentType.DRIVER_REQUIRED);
		if (includeVehicle && vehicleId.isPresent()) {
			required.addAll(DocumentType.VEHICLE_REQUIRED);
		}
		Set<DocumentType> missing = EnumSet.noneOf(DocumentType.class);
		Set<DocumentType> pending = EnumSet.noneOf(DocumentType.class);
		Set<DocumentType> expired = EnumSet.noneOf(DocumentType.class);
		for (DocumentType type : required) {
			UUID expectedVehicle = type.isVehicleDocument() ? vehicleId.orElse(null) : null;
			Optional<DriverDocument> doc = current.stream()
				.filter(d -> d.getType() == type && Objects.equals(d.getVehicleId(), expectedVehicle))
				.findFirst();
			if (doc.isEmpty() || doc.get().getStatus() == DocumentStatus.REJECTED) {
				missing.add(type);
			}
			else if (doc.get().getStatus() == DocumentStatus.PENDING_REVIEW) {
				pending.add(type);
			}
			else if (doc.get().isExpiredOn(today)) {
				expired.add(type);
			}
		}
		return new Readiness(missing, pending, expired, vehicleId.isPresent());
	}
}
