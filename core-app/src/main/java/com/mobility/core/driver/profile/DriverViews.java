package com.mobility.core.driver.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentView;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.driver.vehicle.VehicleView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** API representations. Personal data is always masked; staff views add reviewer and actor ids. */
@Component
class DriverViews {

	record DriverSummary(UUID id, UUID userId, String fullName, String phone, DriverStatus status, Instant createdAt) {
	}

	record BankAccountView(String bankName, String accountName, String accountNumberMasked) {
	}

	record SuspensionView(String reason, Instant noticeAt, Instant suspendedAt, UUID suspendedBy) {
	}

	record ReadinessView(List<String> missing, List<String> pendingReview, List<String> expired,
			boolean hasActiveVehicle) {
	}

	record HistoryView(DriverStatus from, DriverStatus to, String reason, Instant noticeAt, Instant occurredAt,
			UUID actorUserId) {
	}

	record DriverDetail(UUID id, UUID userId, String fullName, String phone, DriverStatus status,
			String nationalIdMasked, BankAccountView bankAccount, SuspensionView suspension, VehicleView vehicle,
			List<DocumentView> documents, ReadinessView readiness, List<HistoryView> history, Instant createdAt) {
	}

	private final DocumentService documents;

	private final VehicleService vehicles;

	private final StatusHistoryRepository history;

	private final ReadinessCalculator readiness;

	DriverViews(DocumentService documents, VehicleService vehicles, StatusHistoryRepository history,
			ReadinessCalculator readiness) {
		this.documents = documents;
		this.vehicles = vehicles;
		this.history = history;
		this.readiness = readiness;
	}

	static DriverSummary summary(Driver d) {
		return new DriverSummary(d.getId(), d.getUserId(), d.getFullName(), d.getPhoneE164(), d.getStatus(),
				d.getCreatedAt());
	}

	@Transactional(readOnly = true)
	DriverDetail detail(Driver d, boolean forStaff) {
		BankAccountView bank = d.getBankAccountNumber() == null && d.getBankName() == null ? null
				: new BankAccountView(d.getBankName(), d.getBankAccountName(), PersonalData.mask(d.getBankAccountNumber()));
		SuspensionView suspension = d.getSuspendedAt() == null ? null
				: new SuspensionView(d.getSuspensionReason(), d.getSuspensionNoticeAt(), d.getSuspendedAt(),
						forStaff ? d.getSuspendedBy() : null);
		Readiness r = readiness.forDispatch(d.getId());
		return new DriverDetail(d.getId(), d.getUserId(), d.getFullName(), d.getPhoneE164(), d.getStatus(),
				PersonalData.mask(d.getNationalId()), bank, suspension, vehicles.activeVehicle(d.getId()).orElse(null),
				documents.currentDocuments(d.getId()).stream().map(doc -> DocumentView.of(doc, forStaff)).toList(),
				new ReadinessView(Readiness.names(r.missing()), Readiness.names(r.pendingReview()),
						Readiness.names(r.expired()), r.hasActiveVehicle()),
				history.findByDriverIdOrderByOccurredAtAsc(d.getId())
					.stream()
					.map(h -> new HistoryView(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getNoticeAt(),
							h.getOccurredAt(), forStaff ? h.getActorUserId() : null))
					.toList(),
				d.getCreatedAt());
	}
}
