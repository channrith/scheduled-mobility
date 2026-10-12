package com.mobility.core.driver.profile;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.driver.DriverEvents.DriverApproved;
import com.mobility.core.driver.DriverEvents.DriverReinstated;
import com.mobility.core.driver.DriverEvents.DriverSuspended;
import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.HomeZone;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.identity.UserAccounts.UserAccount;
import com.mobility.core.shared.crypto.PiiCrypto;
import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver registration, editing, search and status transitions. Each transition writes a status-history
 * row, and each staff action an audit entry, in the same transaction.
 */
@Service
public class DriverOnboardingService {

	/** Same window as the daily document expiry job. */
	private static final int EXPIRY_WARNING_DAYS = 30;

	record RegisterDriver(String phone, String fullName, String nationalId, String licenseNumber, HomeZone homeZone,
			Language preferredLanguage, String bankName, String bankAccountName, String bankAccountNumber) {
	}

	/** {@code null} fields are left unchanged. */
	record UpdateDriver(long expectedVersion, String fullName, String nationalId, String licenseNumber,
			HomeZone homeZone, Language preferredLanguage, String bankName, String bankAccountName,
			String bankAccountNumber) {
	}

	/** @param q name, phone, plate, or an exact national ID / licence number */
	record DriverFilter(DriverStatus status, VehicleClass vehicleClass, boolean expiringSoon, String q) {
	}

	private final DriverRepository drivers;

	private final StatusHistoryRepository history;

	private final ReadinessCalculator readiness;

	private final UserAccounts userAccounts;

	private final PiiCrypto crypto;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final ApplicationEventPublisher events;

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	DriverOnboardingService(DriverRepository drivers, StatusHistoryRepository history, ReadinessCalculator readiness,
			UserAccounts userAccounts, PiiCrypto crypto, AuditLog auditLog, CurrentUserProvider currentUser,
			ApplicationEventPublisher events, NamedParameterJdbcTemplate jdbc, Clock clock) {
		this.drivers = drivers;
		this.history = history;
		this.readiness = readiness;
		this.userAccounts = userAccounts;
		this.crypto = crypto;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.events = events;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// --- Registration and editing --------------------------------------------------------------

	@Transactional
	Driver register(RegisterDriver cmd) {
		Identifier nationalId = identifier(cmd.nationalId(), PersonalData::normalizeNationalId,
				"driver.national-id-invalid");
		if (nationalId != null && drivers.existsByNationalIdHash(nationalId.hash())) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.national-id-taken");
		}
		Identifier license = identifier(cmd.licenseNumber(), PersonalData::normalizeNationalId,
				"driver.license-number-invalid");
		if (license != null && drivers.existsByLicenseNumberHash(license.hash())) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.license-number-taken");
		}
		String bankAccount = bankAccount(cmd.bankAccountNumber());

		UserAccount account = userAccounts.ensureUserWithRole(cmd.phone(), cmd.fullName().strip(), Role.DRIVER);
		if (drivers.existsByUserId(account.userId())) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.already-registered");
		}
		if (cmd.preferredLanguage() != null) {
			userAccounts.updateProfile(account.userId(), null, cmd.preferredLanguage());
		}
		Driver driver = new Driver(account.userId(), cmd.fullName().strip(), account.phoneE164());
		if (nationalId != null) {
			driver.setNationalId(nationalId.value(), nationalId.hash());
		}
		if (license != null) {
			driver.setLicenseNumber(license.value(), license.hash());
		}
		driver.changeHomeZone(cmd.homeZone());
		if (bankAccount != null || !PersonalData.isBlank(cmd.bankName())) {
			driver.setBankAccount(cmd.bankName(), cmd.bankAccountName(), bankAccount);
		}
		drivers.saveAndFlush(driver);
		recordHistory(driver.getId(), new StatusChange(null, DriverStatus.PENDING, null, null));
		auditLog.record("driver.registered", "driver", driver.getId(), Map.of("status", DriverStatus.PENDING));
		return driver;
	}

	/**
	 * Staff edit. The audit entry lists the changed field names, never their values. All checks (including
	 * uniqueness queries) run before anything changes, so the driver is written once.
	 */
	@Transactional
	Driver update(UUID driverId, UpdateDriver cmd) {
		Driver driver = get(driverId);
		if (!Objects.equals(driver.getVersion(), cmd.expectedVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		String fullName = cmd.fullName() == null ? null : cmd.fullName().strip();
		if (fullName != null && fullName.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation");
		}
		Identifier nationalId = identifier(cmd.nationalId(), PersonalData::normalizeNationalId,
				"driver.national-id-invalid");
		if (nationalId != null && nationalId.value().equals(driver.getNationalId())) {
			nationalId = null;
		}
		if (nationalId != null && drivers.existsByNationalIdHashAndIdNot(nationalId.hash(), driverId)) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.national-id-taken");
		}
		Identifier license = identifier(cmd.licenseNumber(), PersonalData::normalizeNationalId,
				"driver.license-number-invalid");
		if (license != null && license.value().equals(driver.getLicenseNumber())) {
			license = null;
		}
		if (license != null && drivers.existsByLicenseNumberHashAndIdNot(license.hash(), driverId)) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.license-number-taken");
		}
		boolean bankChange = cmd.bankName() != null || cmd.bankAccountName() != null || cmd.bankAccountNumber() != null;
		String bankNumber = cmd.bankAccountNumber() == null ? driver.getBankAccountNumber()
				: bankAccount(cmd.bankAccountNumber());

		List<String> changed = new ArrayList<>();
		if (fullName != null && !fullName.equals(driver.getFullName())) {
			driver.changeFullName(fullName);
			changed.add("fullName");
		}
		if (nationalId != null) {
			driver.setNationalId(nationalId.value(), nationalId.hash());
			changed.add("nationalId");
		}
		if (license != null) {
			driver.setLicenseNumber(license.value(), license.hash());
			changed.add("licenseNumber");
		}
		if (cmd.homeZone() != null && cmd.homeZone() != driver.getHomeZone()) {
			driver.changeHomeZone(cmd.homeZone());
			changed.add("homeZone");
		}
		if (bankChange) {
			driver.setBankAccount(cmd.bankName() == null ? driver.getBankName() : cmd.bankName(),
					cmd.bankAccountName() == null ? driver.getBankAccountName() : cmd.bankAccountName(), bankNumber);
			changed.add("bankAccount");
		}
		if (!changed.isEmpty()) {
			drivers.saveAndFlush(driver);
		}
		if (changed.contains("fullName") || cmd.preferredLanguage() != null) {
			userAccounts.updateProfile(driver.getUserId(), changed.contains("fullName") ? driver.getFullName() : null,
					cmd.preferredLanguage());
		}
		if (!changed.isEmpty()) {
			auditLog.record("driver.updated", "driver", driverId, Map.of("fields", changed));
		}
		return driver;
	}

	// --- Queries -------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	Driver get(UUID driverId) {
		return drivers.findById(driverId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "driver.not-found"));
	}

	@Transactional(readOnly = true)
	Driver getByUserId(UUID userId) {
		return drivers.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "driver.not-found"));
	}

	/** Newest first. Personal data is searched only by exact match on its blind index. */
	@Transactional(readOnly = true)
	Page<Driver> list(DriverFilter filter, int page, int size) {
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
		Instant now = clock.instant();
		StringBuilder where = new StringBuilder(" WHERE true");
		MapSqlParameterSource params = new MapSqlParameterSource("now", Timestamp.from(now));
		String activeVehicle = "SELECT 1 FROM driver.driver_vehicle_assignments a JOIN driver.vehicles v ON v.id = a.vehicle_id"
				+ " WHERE a.driver_id = d.id AND a.valid_from <= :now AND (a.valid_to IS NULL OR a.valid_to > :now)";
		if (filter.status() != null) {
			where.append(" AND d.status = :status");
			params.addValue("status", filter.status().name());
		}
		if (filter.vehicleClass() != null) {
			where.append(" AND EXISTS (").append(activeVehicle).append(" AND v.vehicle_class = :vehicleClass)");
			params.addValue("vehicleClass", filter.vehicleClass().name());
		}
		if (filter.expiringSoon()) {
			where.append(" AND EXISTS (SELECT 1 FROM driver.driver_documents x WHERE x.driver_id = d.id"
					+ " AND x.status = 'APPROVED' AND x.expires_on <= :cutoff)");
			params.addValue("cutoff", Date.valueOf(BusinessTime.today(clock).plusDays(EXPIRY_WARNING_DAYS)));
		}
		if (filter.q() != null && !filter.q().isBlank()) {
			String q = filter.q().strip();
			List<String> any = new ArrayList<>();
			any.add("lower(d.full_name) LIKE :name");
			params.addValue("name", "%" + q.toLowerCase(Locale.ROOT) + "%");
			String digits = q.replaceAll("\\D", "");
			if (digits.length() >= 3) {
				any.add("d.phone_e164 LIKE :phone");
				params.addValue("phone", "%" + (digits.startsWith("0") ? "855" + digits.substring(1) : digits) + "%");
			}
			String plate = q.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
			if (!plate.isEmpty()) {
				any.add("EXISTS (" + activeVehicle + " AND regexp_replace(v.plate_number, '[^A-Z0-9]', '', 'g') LIKE :plate)");
				params.addValue("plate", "%" + plate + "%");
			}
			PersonalData.normalizeNationalId(q).ifPresent(id -> {
				any.add("d.national_id_hash = :idHash OR d.license_number_hash = :idHash");
				params.addValue("idHash", crypto.blindIndex(id));
			});
			where.append(" AND (").append(String.join(" OR ", any)).append(")");
		}
		Long total = jdbc.queryForObject("SELECT count(*) FROM driver.drivers d" + where, params, Long.class);
		params.addValue("limit", request.getPageSize()).addValue("offset", request.getOffset());
		List<UUID> ids = jdbc.queryForList("SELECT d.id FROM driver.drivers d" + where
				+ " ORDER BY d.created_at DESC, d.id LIMIT :limit OFFSET :offset", params, UUID.class);
		Map<UUID, Driver> byId = drivers.findAllById(ids).stream().collect(Collectors.toMap(Driver::getId, Function.identity()));
		return new PageImpl<>(ids.stream().map(byId::get).toList(), request, total == null ? 0 : total);
	}

	// --- Transitions ---------------------------------------------------------------------------

	/** The driver themselves: all required documents uploaded. Not a staff action, so not audited. */
	@Transactional
	Driver submitDocuments(UUID userId) {
		Driver driver = getByUserId(userId);
		StatusChange change = driver.submitDocuments(readiness.forOnboarding(driver.getId()));
		drivers.saveAndFlush(driver);
		recordHistory(driver.getId(), change);
		return driver;
	}

	/** Staff, during in-person onboarding: same guard as the driver's own submission. */
	@Transactional
	Driver submitDocumentsForDriver(UUID driverId) {
		return staffTransition(driverId, "driver.documents-submitted",
				d -> d.submitDocuments(readiness.forOnboarding(driverId)));
	}

	@Transactional
	Driver startTraining(UUID driverId) {
		return staffTransition(driverId, "driver.training-started",
				d -> d.startTraining(readiness.forOnboarding(driverId)));
	}

	@Transactional
	Driver approve(UUID driverId) {
		Driver driver = staffTransition(driverId, "driver.approved", d -> d.approve(readiness.forDispatch(driverId)));
		events.publishEvent(new DriverApproved(driver.getId(), driver.getUserId()));
		return driver;
	}

	@Transactional
	Driver reject(UUID driverId, String reason) {
		return staffTransition(driverId, "driver.rejected", d -> d.reject(reason));
	}

	@Transactional
	Driver suspend(UUID driverId, String reason, Instant noticeAt) {
		UUID actor = currentUser.require().userId();
		Driver driver = staffTransition(driverId, "driver.suspended",
				d -> d.suspend(reason, noticeAt, actor, clock.instant()));
		events.publishEvent(new DriverSuspended(driver.getId(), driver.getUserId(), driver.getSuspensionNoticeAt()));
		return driver;
	}

	@Transactional
	Driver reinstate(UUID driverId, String reason) {
		Driver driver = staffTransition(driverId, "driver.reinstated",
				d -> d.reinstate(reason, readiness.forDispatch(driverId)));
		events.publishEvent(new DriverReinstated(driver.getId(), driver.getUserId()));
		return driver;
	}

	private Driver staffTransition(UUID driverId, String action, Function<Driver, StatusChange> transition) {
		Driver driver = get(driverId);
		StatusChange change = transition.apply(driver);
		// Flush now so a concurrent change (version conflict) fails here as 409, before auditing.
		drivers.saveAndFlush(driver);
		recordHistory(driverId, change);
		// The reason stays in the driver's status history; the audit log must not hold free text/PII.
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("from", change.from());
		details.put("to", change.to());
		if (change.noticeAt() != null) {
			details.put("noticeAt", change.noticeAt().toString());
		}
		auditLog.record(action, "driver", driverId, details);
		return driver;
	}

	private void recordHistory(UUID driverId, StatusChange change) {
		UUID actor = currentUser.current().map(u -> u.userId()).orElse(null);
		history.save(new StatusHistoryEntry(driverId, change.from(), change.to(), actor, change.reason(),
				change.noticeAt(), clock.instant()));
	}

	// --- Personal data -------------------------------------------------------------------------

	private record Identifier(String value, byte[] hash) {
	}

	/** Normalizes an ID-like number and computes its blind index; blank means "not provided". */
	private Identifier identifier(String raw, Function<String, java.util.Optional<String>> normalizer, String errorCode) {
		if (PersonalData.isBlank(raw)) {
			return null;
		}
		String value = normalizer.apply(raw).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, errorCode));
		return new Identifier(value, crypto.blindIndex(value));
	}

	private static String bankAccount(String raw) {
		if (PersonalData.isBlank(raw)) {
			return null;
		}
		return PersonalData.normalizeBankAccount(raw)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "driver.bank-account-invalid"));
	}
}
