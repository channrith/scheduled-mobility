package com.mobility.core.driver.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentStatus;
import com.mobility.core.driver.document.DocumentType;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Transactional
class LocalApprovedDriverSeedIT {

	@Autowired TransactionTemplate transactions;
	@Autowired JdbcTemplate jdbc;
	@Autowired UserAccounts accounts;
	@Autowired DriverRepository drivers;
	@Autowired DriverOnboardingService onboarding;
	@Autowired DocumentService documents;
	@Autowired VehicleService vehicles;
	@Autowired ReadinessCalculator readiness;
	@Autowired Clock clock;

	@Test
	void reusesExistingDriverAndIsIdempotentWithApprovedDocumentsAndAssignment() {
		var account = accounts.ensureUserWithRole(LocalApprovedDriverSeed.PHONE, "Existing Driver", Role.PASSENGER);
		Driver original = onboarding.register(new DriverOnboardingService.RegisterDriver(LocalApprovedDriverSeed.PHONE,
				"Existing Driver", null, null, null, null, null, null, null));
		var previousContext = SecurityContextHolder.getContext();

		seed().run(null);

		Driver seeded = drivers.findByUserId(account.userId()).orElseThrow();
		assertThat(seeded.getId()).isEqualTo(original.getId());
		assertThat(seeded.getFullName()).isEqualTo("Existing Driver");
		assertThat(seeded.getStatus()).isEqualTo(DriverStatus.APPROVED);
		assertThat(documents.currentDocuments(seeded.getId())).hasSize(4)
			.allMatch(d -> d.getStatus() == DocumentStatus.APPROVED)
			.extracting(d -> d.getType()).containsExactlyInAnyOrder(DocumentType.values());
		assertThat(readiness.forDispatch(seeded.getId()).allApprovedAndValid()).isTrue();
		assertThat(vehicles.activeVehicle(seeded.getId())).isPresent();
		assertThat(jdbc.queryForList("SELECT role FROM identity.user_roles WHERE user_id = ?", String.class,
				account.userId())).containsExactlyInAnyOrder("PASSENGER", "DRIVER");
		assertThat(SecurityContextHolder.getContext()).isSameAs(previousContext);

		var before = snapshot(seeded.getId());
		seed().run(null);
		assertThat(snapshot(seeded.getId())).isEqualTo(before);
		assertThat(SecurityContextHolder.getContext()).isSameAs(previousContext);
	}

	@Test
	void createsDriverForExistingPassengerWithoutReplacingTheirUserId() {
		var account = accounts.ensureUserWithRole(LocalApprovedDriverSeed.PHONE, "Existing Passenger", Role.PASSENGER);
		seed().run(null);
		assertThat(drivers.findByUserId(account.userId())).isPresent()
			.get().extracting(Driver::getStatus).isEqualTo(DriverStatus.APPROVED);
	}

	private List<?> snapshot(UUID driverId) {
		return List.of(jdbc.queryForList("SELECT * FROM driver.drivers WHERE id = ?", driverId),
				jdbc.queryForList("SELECT * FROM driver.driver_documents WHERE driver_id = ? ORDER BY id", driverId),
				jdbc.queryForList("SELECT * FROM driver.driver_vehicle_assignments WHERE driver_id = ? ORDER BY id", driverId),
				jdbc.queryForList("SELECT * FROM driver.driver_status_history WHERE driver_id = ? ORDER BY id", driverId),
				jdbc.queryForObject("SELECT count(*) FROM audit.audit_logs", Long.class));
	}

	private LocalApprovedDriverSeed seed() {
		var environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		return new LocalApprovedDriverSeed(true, environment, transactions, jdbc, accounts, drivers, onboarding,
				documents, vehicles, readiness, clock);
	}
}
