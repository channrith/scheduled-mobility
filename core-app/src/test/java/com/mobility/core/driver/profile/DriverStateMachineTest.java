package com.mobility.core.driver.profile;

import static com.mobility.core.driver.DriverStatus.APPROVED;
import static com.mobility.core.driver.DriverStatus.DOCS_SUBMITTED;
import static com.mobility.core.driver.DriverStatus.PENDING;
import static com.mobility.core.driver.DriverStatus.REJECTED;
import static com.mobility.core.driver.DriverStatus.SUSPENDED;
import static com.mobility.core.driver.DriverStatus.TRAINING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.document.DocumentType;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class DriverStateMachineTest {

	static final Instant NOW = Instant.parse("2026-09-27T03:00:00Z");

	static final UUID ADMIN = UUID.randomUUID();

	static final String REASON = "Repeated no-shows reported by clients";

	static final Readiness READY = new Readiness(Set.of(), Set.of(), Set.of(), true);

	/** Every operation, and the only statuses it may start from. */
	static final Map<String, Operation> OPERATIONS = Map.of(
			"submitDocuments", new Operation(EnumSet.of(PENDING), DOCS_SUBMITTED, d -> d.submitDocuments(READY)),
			"startTraining", new Operation(EnumSet.of(DOCS_SUBMITTED), TRAINING, d -> d.startTraining(READY)),
			"approve", new Operation(EnumSet.of(TRAINING), APPROVED, d -> d.approve(READY)),
			"reject", new Operation(EnumSet.of(PENDING, DOCS_SUBMITTED, TRAINING), REJECTED, d -> d.reject(REASON)),
			"suspend", new Operation(EnumSet.of(APPROVED), SUSPENDED, d -> d.suspend(REASON, null, ADMIN, NOW)),
			"reinstate", new Operation(EnumSet.of(SUSPENDED), APPROVED, d -> d.reinstate(REASON, READY)));

	record Operation(Set<DriverStatus> allowedFrom, DriverStatus target, Function<Driver, StatusChange> action) {
	}

	static Stream<Arguments> everyOperationFromEveryStatus() {
		return OPERATIONS.keySet()
			.stream()
			.sorted()
			.flatMap(op -> Stream.of(DriverStatus.values()).map(status -> Arguments.of(op, status)));
	}

	@ParameterizedTest(name = "{0} from {1}")
	@MethodSource("everyOperationFromEveryStatus")
	void transitionsAreAllowedOnlyFromTheirSourceStatuses(String name, DriverStatus from) {
		Operation op = OPERATIONS.get(name);
		Driver driver = driverIn(from);

		if (op.allowedFrom().contains(from)) {
			StatusChange change = op.action().apply(driver);
			assertThat(change.from()).isEqualTo(from);
			assertThat(change.to()).isEqualTo(op.target());
			assertThat(driver.getStatus()).isEqualTo(op.target());
			assertThat(Driver.TRANSITIONS.get(from)).contains(op.target());
		}
		else {
			assertThatThrownBy(() -> op.action().apply(driver)).satisfies(ex -> {
				assertProblem(ex, HttpStatus.CONFLICT, "driver.invalid-transition");
				assertThat(((ApiException) ex).getProperties()).containsEntry("from", from);
			});
			assertThat(driver.getStatus()).isEqualTo(from);
		}
	}

	@Test
	void transitionTableMatchesTheOperations() {
		for (DriverStatus from : DriverStatus.values()) {
			Set<DriverStatus> reachable = EnumSet.noneOf(DriverStatus.class);
			OPERATIONS.values().stream().filter(op -> op.allowedFrom().contains(from)).forEach(op -> reachable.add(op.target()));
			assertThat(Driver.TRANSITIONS.get(from)).as("from %s", from).isEqualTo(reachable);
		}
	}

	@Test
	void rejectedIsFinal() {
		assertThat(Driver.TRANSITIONS.get(REJECTED)).isEmpty();
		assertThat(driverIn(REJECTED).canUploadDocuments()).isFalse();
	}

	@Nested
	class Guards {

		@Test
		void submitRequiresEveryRequiredDocument() {
			Readiness missing = new Readiness(Set.of(DocumentType.DRIVING_LICENSE, DocumentType.PROFILE_PHOTO), Set.of(),
					Set.of(), false);

			assertThatThrownBy(() -> driverIn(PENDING).submitDocuments(missing)).satisfies(ex -> {
				assertProblem(ex, HttpStatus.CONFLICT, "driver.documents-missing");
				assertThat(((ApiException) ex).getProperties().get("missing"))
					.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
					.containsExactly("DRIVING_LICENSE", "PROFILE_PHOTO");
			});
		}

		@Test
		void submitDoesNotNeedApprovalOrVehicle() {
			Readiness uploaded = new Readiness(Set.of(), Set.of(DocumentType.NATIONAL_ID), Set.of(), false);

			assertThat(driverIn(PENDING).submitDocuments(uploaded).to()).isEqualTo(DOCS_SUBMITTED);
		}

		@Test
		void trainingRequiresApprovedDocuments() {
			Readiness pending = new Readiness(Set.of(), Set.of(DocumentType.NATIONAL_ID), Set.of(), true);

			assertThatThrownBy(() -> driverIn(DOCS_SUBMITTED).startTraining(pending))
				.satisfies(ex -> assertProblem(ex, HttpStatus.CONFLICT, "driver.documents-not-approved"));
		}

		@Test
		void trainingDoesNotNeedAVehicle() {
			Readiness noVehicle = new Readiness(Set.of(), Set.of(), Set.of(), false);

			assertThat(driverIn(DOCS_SUBMITTED).startTraining(noVehicle).to()).isEqualTo(TRAINING);
		}

		@Test
		void approvalRequiresAVehicle() {
			Readiness noVehicle = new Readiness(Set.of(), Set.of(), Set.of(), false);

			assertThatThrownBy(() -> driverIn(TRAINING).approve(noVehicle))
				.satisfies(ex -> assertProblem(ex, HttpStatus.CONFLICT, "driver.vehicle-required"));
		}

		@Test
		void approvalRejectsExpiredDocuments() {
			Readiness expired = new Readiness(Set.of(), Set.of(), Set.of(DocumentType.VEHICLE_REGISTRATION), true);

			assertThatThrownBy(() -> driverIn(TRAINING).approve(expired))
				.satisfies(ex -> assertProblem(ex, HttpStatus.CONFLICT, "driver.documents-expired"));
		}

		@Test
		void rejectionRequiresAReason() {
			assertThatThrownBy(() -> driverIn(TRAINING).reject("  no  "))
				.satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "driver.reason-required"));
		}
	}

	@Nested
	class Suspension {

		@Test
		void recordsReasonNoticeTimeActorAndSuspensionTime() {
			Driver driver = driverIn(APPROVED);
			Instant notice = NOW.minus(Duration.ofHours(2));

			StatusChange change = driver.suspend("  " + REASON + "  ", notice, ADMIN, NOW);

			assertThat(change).isEqualTo(new StatusChange(APPROVED, SUSPENDED, REASON, notice));
			assertThat(driver.getSuspensionReason()).isEqualTo(REASON);
			assertThat(driver.getSuspensionNoticeAt()).isEqualTo(notice);
			assertThat(driver.getSuspendedAt()).isEqualTo(NOW);
			assertThat(driver.getSuspendedBy()).isEqualTo(ADMIN);
		}

		@Test
		void noticeDefaultsToNow() {
			Driver driver = driverIn(APPROVED);

			assertThat(driver.suspend(REASON, null, ADMIN, NOW).noticeAt()).isEqualTo(NOW);
			assertThat(driver.getSuspensionNoticeAt()).isEqualTo(NOW);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "too short" })
		void requiresAMeaningfulReason(String reason) {
			Driver driver = driverIn(APPROVED);

			assertThatThrownBy(() -> driver.suspend(reason, null, ADMIN, NOW))
				.satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "driver.reason-required"));
			assertThat(driver.getStatus()).isEqualTo(APPROVED);
			assertThat(driver.getSuspensionReason()).isNull();
		}

		@Test
		void noticeCannotBeInTheFuture() {
			Driver driver = driverIn(APPROVED);

			assertThatThrownBy(() -> driver.suspend(REASON, NOW.plus(Duration.ofMinutes(5)), ADMIN, NOW))
				.satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "driver.notice-in-future"));
			assertThat(driver.getStatus()).isEqualTo(APPROVED);
		}

		@Test
		void smallClockSkewOnNoticeIsTolerated() {
			assertThat(driverIn(APPROVED).suspend(REASON, NOW.plusSeconds(30), ADMIN, NOW).to()).isEqualTo(SUSPENDED);
		}

		@ParameterizedTest
		@EnumSource(value = DriverStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
		void onlyApprovedDriversCanBeSuspended(DriverStatus status) {
			assertThatThrownBy(() -> driverIn(status).suspend(REASON, null, ADMIN, NOW))
				.satisfies(ex -> assertProblem(ex, HttpStatus.CONFLICT, "driver.invalid-transition"));
		}

		@Test
		void reinstatementRequiresAReasonAndClearsTheActiveSuspension() {
			Driver driver = driverIn(SUSPENDED);

			assertThatThrownBy(() -> driver.reinstate("", READY))
				.satisfies(ex -> assertProblem(ex, HttpStatus.BAD_REQUEST, "driver.reason-required"));

			StatusChange change = driver.reinstate("Appeal reviewed and accepted", READY);

			assertThat(change.to()).isEqualTo(APPROVED);
			assertThat(change.reason()).isEqualTo("Appeal reviewed and accepted");
			assertThat(driver.getSuspensionReason()).isNull();
			assertThat(driver.getSuspensionNoticeAt()).isNull();
		}

		@Test
		void reinstatementRequiresTheDriverToStillBeDispatchReady() {
			Readiness expired = new Readiness(Set.of(), Set.of(), Set.of(DocumentType.DRIVING_LICENSE), true);

			assertThatThrownBy(() -> driverIn(SUSPENDED).reinstate(REASON, expired))
				.satisfies(ex -> assertProblem(ex, HttpStatus.CONFLICT, "driver.documents-expired"));
		}
	}

	/** Walks a new driver through the legal path to {@code status}. */
	static Driver driverIn(DriverStatus status) {
		Driver driver = new Driver(UUID.randomUUID(), "Sok Dara", "+85512345678");
		switch (status) {
			case PENDING -> {
			}
			case REJECTED -> driver.reject(REASON);
			default -> {
				driver.submitDocuments(READY);
				if (status == DOCS_SUBMITTED) {
					break;
				}
				driver.startTraining(READY);
				if (status == TRAINING) {
					break;
				}
				driver.approve(READY);
				if (status == SUSPENDED) {
					driver.suspend(REASON, null, ADMIN, NOW);
				}
			}
		}
		assertThat(driver.getStatus()).isEqualTo(status);
		return driver;
	}

	static void assertProblem(Throwable ex, HttpStatus status, String code) {
		assertThat(ex).isInstanceOf(ApiException.class);
		assertThat(((ApiException) ex).getStatus()).isEqualTo(status);
		assertThat(((ApiException) ex).getCode()).isEqualTo(code);
	}
}
