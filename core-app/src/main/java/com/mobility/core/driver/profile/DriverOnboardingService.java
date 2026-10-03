package com.mobility.core.driver.profile;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.driver.DriverEvents.DriverApproved;
import com.mobility.core.driver.DriverEvents.DriverReinstated;
import com.mobility.core.driver.DriverEvents.DriverSuspended;
import com.mobility.core.driver.DriverStatus;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.identity.UserAccounts.UserAccount;
import com.mobility.core.shared.crypto.PiiCrypto;
import com.mobility.core.shared.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver registration and status transitions. Each transition writes a status-history row, and each
 * staff action an audit entry, in the same transaction.
 */
@Service
public class DriverOnboardingService {

	record RegisterDriver(String phone, String fullName, String nationalId, String bankName, String bankAccountName,
			String bankAccountNumber) {
	}

	private final DriverRepository drivers;

	private final StatusHistoryRepository history;

	private final ReadinessCalculator readiness;

	private final UserAccounts userAccounts;

	private final PiiCrypto crypto;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	DriverOnboardingService(DriverRepository drivers, StatusHistoryRepository history, ReadinessCalculator readiness,
			UserAccounts userAccounts, PiiCrypto crypto, AuditLog auditLog, CurrentUserProvider currentUser,
			ApplicationEventPublisher events, Clock clock) {
		this.drivers = drivers;
		this.history = history;
		this.readiness = readiness;
		this.userAccounts = userAccounts;
		this.crypto = crypto;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	Driver register(RegisterDriver cmd) {
		Driver driver;
		String nationalId = null;
		byte[] nationalIdHash = null;
		if (!PersonalData.isBlank(cmd.nationalId())) {
			nationalId = PersonalData.normalizeNationalId(cmd.nationalId())
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "driver.national-id-invalid"));
			nationalIdHash = crypto.blindIndex(nationalId);
			if (drivers.existsByNationalIdHash(nationalIdHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "driver.national-id-taken");
			}
		}
		String bankAccount = null;
		if (!PersonalData.isBlank(cmd.bankAccountNumber())) {
			bankAccount = PersonalData.normalizeBankAccount(cmd.bankAccountNumber())
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "driver.bank-account-invalid"));
		}

		UserAccount account = userAccounts.ensureUserWithRole(cmd.phone(), cmd.fullName().strip(), Role.DRIVER);
		if (drivers.existsByUserId(account.userId())) {
			throw new ApiException(HttpStatus.CONFLICT, "driver.already-registered");
		}
		driver = new Driver(account.userId(), cmd.fullName().strip(), account.phoneE164());
		if (nationalId != null) {
			driver.setNationalId(nationalId, nationalIdHash);
		}
		if (bankAccount != null || !PersonalData.isBlank(cmd.bankName())) {
			driver.setBankAccount(cmd.bankName(), cmd.bankAccountName(), bankAccount);
		}
		drivers.saveAndFlush(driver);
		recordHistory(driver.getId(), new StatusChange(null, DriverStatus.PENDING, null, null));
		auditLog.record("driver.registered", "driver", driver.getId(), Map.of("status", DriverStatus.PENDING));
		return driver;
	}

	@Transactional(readOnly = true)
	Driver get(UUID driverId) {
		return drivers.findById(driverId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "driver.not-found"));
	}

	@Transactional(readOnly = true)
	Driver getByUserId(UUID userId) {
		return drivers.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "driver.not-found"));
	}

	@Transactional(readOnly = true)
	Page<Driver> list(DriverStatus status, int page, int size) {
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
				Sort.by(Sort.Direction.DESC, "createdAt"));
		return status == null ? drivers.findAll(request) : drivers.findByStatus(status, request);
	}

	/** The driver themselves: all required documents uploaded. Not a staff action, so not audited. */
	@Transactional
	Driver submitDocuments(UUID userId) {
		Driver driver = getByUserId(userId);
		StatusChange change = driver.submitDocuments(readiness.forOnboarding(driver.getId()));
		drivers.saveAndFlush(driver);
		recordHistory(driver.getId(), change);
		return driver;
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
}
