package com.mobility.core.driver.profile;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.audit.AuditTrail;
import com.mobility.core.audit.AuditTrail.AuditEntry;
import com.mobility.core.audit.AuditTrail.DetailMatch;
import com.mobility.core.audit.AuditTrail.Target;
import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.HomeZone;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentView;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.driver.vehicle.VehicleService.AssignmentView;
import com.mobility.core.driver.vehicle.VehicleView;
import com.mobility.core.identity.UserDirectory;
import com.mobility.core.identity.UserDirectory.UserSummary;
import com.mobility.core.shared.i18n.Language;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** API representations. Personal data is always masked; staff views add reviewer, uploader and actor ids. */
@Component
class DriverViews {

	record VehicleBrief(String plateNumber, VehicleClass vehicleClass) {
	}

	record DriverSummary(UUID id, UUID userId, String fullName, String phone, DriverStatus status, HomeZone homeZone,
			VehicleBrief vehicle, boolean documentsExpiringSoon, Instant createdAt) {
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

	/**
	 * @param version send back when editing (optimistic locking)
	 * @param vehicle the vehicle assigned right now
	 * @param vehicleAssignments all assignments, newest first (future, current and past)
	 */
	record DriverDetail(UUID id, UUID userId, String fullName, String phone, DriverStatus status, Long version,
			String nationalIdMasked, String licenseNumberMasked, HomeZone homeZone, Language preferredLanguage,
			BankAccountView bankAccount, SuspensionView suspension, VehicleView vehicle,
			List<AssignmentView> vehicleAssignments, List<DocumentView> documents, ReadinessView readiness,
			List<HistoryView> history, Instant createdAt) {
	}

	record AuditView(UUID id, Instant occurredAt, UUID actorUserId, String actorName, List<String> actorRoles,
			String action, String targetType, String targetId, Map<String, Object> details) {
	}

	private final DocumentService documents;

	private final VehicleService vehicles;

	private final StatusHistoryRepository history;

	private final ReadinessCalculator readiness;

	private final UserDirectory users;

	private final AuditTrail auditTrail;

	DriverViews(DocumentService documents, VehicleService vehicles, StatusHistoryRepository history,
			ReadinessCalculator readiness, UserDirectory users, AuditTrail auditTrail) {
		this.documents = documents;
		this.vehicles = vehicles;
		this.history = history;
		this.readiness = readiness;
		this.users = users;
		this.auditTrail = auditTrail;
	}

	/** One batch of lookups for a page of drivers. */
	@Transactional(readOnly = true)
	List<DriverSummary> summaries(List<Driver> page) {
		List<UUID> ids = page.stream().map(Driver::getId).toList();
		Map<UUID, VehicleView> active = vehicles.activeVehicles(ids);
		Set<UUID> expiring = documents.driversWithExpiringDocuments(ids);
		return page.stream().map(d -> {
			VehicleView v = active.get(d.getId());
			return new DriverSummary(d.getId(), d.getUserId(), d.getFullName(), d.getPhoneE164(), d.getStatus(),
					d.getHomeZone(), v == null ? null : new VehicleBrief(v.plateNumber(), v.vehicleClass()),
					expiring.contains(d.getId()), d.getCreatedAt());
		}).toList();
	}

	@Transactional(readOnly = true)
	DriverDetail detail(Driver d, boolean forStaff) {
		BankAccountView bank = d.getBankAccountNumber() == null && d.getBankName() == null ? null
				: new BankAccountView(d.getBankName(), d.getBankAccountName(), PersonalData.mask(d.getBankAccountNumber()));
		SuspensionView suspension = d.getSuspendedAt() == null ? null
				: new SuspensionView(d.getSuspensionReason(), d.getSuspensionNoticeAt(), d.getSuspendedAt(),
						forStaff ? d.getSuspendedBy() : null);
		Readiness r = readiness.forDispatch(d.getId());
		UserSummary user = users.find(List.of(d.getUserId())).get(d.getUserId());
		return new DriverDetail(d.getId(), d.getUserId(), d.getFullName(), d.getPhoneE164(), d.getStatus(),
				d.getVersion(), PersonalData.mask(d.getNationalId()), PersonalData.mask(d.getLicenseNumber()),
				d.getHomeZone(), user == null ? null : user.preferredLanguage(), bank, suspension,
				vehicles.activeVehicle(d.getId()).orElse(null), vehicles.assignments(d.getId()),
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

	/** Staff accounts created by phone may have no name yet: show the phone number then. */
	private static String displayName(UserSummary user) {
		return user.fullName() == null || user.fullName().isBlank() ? user.phoneE164() : user.fullName();
	}

	/** Everything audited about the driver: the driver record, their documents and their user account. */
	@Transactional(readOnly = true)
	List<AuditView> auditTrail(Driver d, int limit) {
		List<AuditEntry> entries = auditTrail.find(List.of(new Target("driver", d.getId().toString()),
				new DetailMatch("driver_document", "driverId", d.getId().toString()),
				new Target("user", d.getUserId().toString())), limit);
		Set<UUID> actors = new HashSet<>();
		entries.stream().map(AuditEntry::actorUserId).filter(java.util.Objects::nonNull).forEach(actors::add);
		Map<UUID, UserSummary> names = users.find(actors);
		return entries.stream()
			.map(e -> new AuditView(e.id(), e.occurredAt(), e.actorUserId(),
					e.actorUserId() == null ? null
							: java.util.Optional.ofNullable(names.get(e.actorUserId())).map(DriverViews::displayName).orElse(null),
					e.actorRoles(), e.action(), e.targetType(), e.targetId(), e.details()))
			.toList();
	}
}
