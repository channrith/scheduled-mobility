package com.mobility.core.driver.profile;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.HomeZone;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentStatus;
import com.mobility.core.driver.document.DocumentType;
import com.mobility.core.driver.document.DriverDocument;
import com.mobility.core.driver.vehicle.VehicleOwnership;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.driver.vehicle.VehicleService.VehicleData;
import com.mobility.core.driver.vehicle.VehicleStatus;
import com.mobility.core.driver.vehicle.VehicleView;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicitly opted-in local fixture. All writes use the normal domain services. */
@Component
class LocalApprovedDriverSeed implements ApplicationRunner {

	static final String PHONE = "012898339";

	static final String PLATE = "LOCAL-SEED-12898339";

	private static final Logger log = LoggerFactory.getLogger(LocalApprovedDriverSeed.class);

	private final boolean enabled;

	private final Environment environment;

	private final TransactionTemplate transactions;

	private final JdbcTemplate jdbc;

	private final UserAccounts accounts;

	private final DriverRepository drivers;

	private final DriverOnboardingService onboarding;

	private final DocumentService documents;

	private final VehicleService vehicles;

	private final ReadinessCalculator readiness;

	private final Clock clock;

	LocalApprovedDriverSeed(@Value("${LOCAL_APPROVED_DRIVER_SEED_ENABLED:false}") boolean enabled,
			Environment environment, TransactionTemplate transactions, JdbcTemplate jdbc, UserAccounts accounts,
			DriverRepository drivers, DriverOnboardingService onboarding, DocumentService documents,
			VehicleService vehicles, ReadinessCalculator readiness, Clock clock) {
		this.enabled = enabled;
		this.environment = environment;
		this.transactions = transactions;
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.drivers = drivers;
		this.onboarding = onboarding;
		this.documents = documents;
		this.vehicles = vehicles;
		this.readiness = readiness;
		this.clock = clock;
		if (enabled) {
			requireLocalProfile(environment);
		}
	}

	static void requireLocalProfile(Environment environment) {
		Set<String> blocked = Set.of("stage", "staging", "prod", "production");
		List<String> profiles = Arrays.stream(environment.getActiveProfiles())
			.map(p -> p.toLowerCase(Locale.ROOT)).toList();
		if (!profiles.contains("local") || profiles.stream().anyMatch(blocked::contains)) {
			throw new IllegalStateException("LOCAL_APPROVED_DRIVER_SEED_ENABLED requires the local profile "
					+ "and is forbidden with staging/production profiles");
		}
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		requireLocalProfile(environment);
		UUID driverId = transactions.execute(status -> seed());
		log.warn("Local APPROVED driver fixture ready: driverId={}. Synthetic documents; local development only.",
				driverId);
	}

	private UUID seed() {
		// Serialize concurrent local startups before looking up or creating any fixture records.
		jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", 12898339L);
		var account = accounts.ensureUserWithRole(PHONE, "Local Test Driver 012898339", Role.DRIVER);
		var previous = SecurityContextHolder.getContext();
		var context = SecurityContextHolder.createEmptyContext();
		// Startup-only audit/reviewer identity: never exposed as an access token or used by HTTP security.
		// The fixture driver gets DRIVER only, not ADMIN. Restore the caller's context even on failure.
		Jwt actor = Jwt.withTokenValue("local-approved-driver-seed")
			.header("alg", "RS256").subject(account.userId().toString())
			.claim("roles", List.of("DRIVER")).build();
		context.setAuthentication(new JwtAuthenticationToken(actor,
				List.of(new SimpleGrantedAuthority("ROLE_DRIVER"))));
		try {
			SecurityContextHolder.setContext(context);
			Driver driver = drivers.findByUserId(account.userId()).orElseGet(() -> onboarding.register(
					new DriverOnboardingService.RegisterDriver(PHONE, "Local Test Driver 012898339", null, null,
							HomeZone.SEN_SOK, Language.EN, null, null, null)));
			if (driver.getStatus() == DriverStatus.REJECTED) {
				throw new IllegalStateException("Local fixture driver is REJECTED; domain rules forbid reopening it");
			}
			UUID driverId = driver.getId();
			VehicleView vehicle = vehicles.activeVehicle(driverId).orElseGet(() -> assignVehicle(driverId));
			if (vehicle.status() != VehicleStatus.ACTIVE) {
				throw new IllegalStateException("Local fixture's assigned vehicle must be ACTIVE");
			}
			for (DocumentType type : DocumentType.values()) {
				ensureDocument(driver.getId(), type, type.isVehicleDocument() ? vehicle.id() : null);
			}
			if (driver.getStatus() == DriverStatus.PENDING) {
				driver = onboarding.submitDocumentsForDriver(driver.getId());
			}
			if (driver.getStatus() == DriverStatus.DOCS_SUBMITTED) {
				driver = onboarding.startTraining(driver.getId());
			}
			if (driver.getStatus() == DriverStatus.TRAINING) {
				driver = onboarding.approve(driver.getId());
			}
			if (driver.getStatus() == DriverStatus.SUSPENDED) {
				driver = onboarding.reinstate(driver.getId(), "Explicit local approved-driver fixture reset");
			}
			Readiness ready = readiness.forDispatch(driver.getId());
			if (driver.getStatus() != DriverStatus.APPROVED || !ready.hasActiveVehicle() || !ready.allApprovedAndValid()) {
				throw new IllegalStateException("Local approved-driver fixture did not satisfy dispatch readiness");
			}
			return driver.getId();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	private VehicleView assignVehicle(UUID driverId) {
		VehicleView vehicle = vehicles.list(PLATE, null, null, 0, 100).getContent().stream()
			.map(VehicleService.VehicleListItem::vehicle).filter(v -> PLATE.equals(v.plateNumber())).findFirst()
			.orElseGet(() -> vehicles.register(new VehicleData(PLATE, VehicleClass.CAR, "Local", "Test fixture",
					"White", (short) 2024, (short) 4, VehicleOwnership.DRIVER, null, null)));
		vehicles.assign(driverId, vehicle.id(), null, null);
		return vehicle;
	}

	private void ensureDocument(UUID driverId, DocumentType type, UUID vehicleId) {
		LocalDate today = BusinessTime.today(clock);
		DriverDocument document = documents.currentDocuments(driverId).stream()
			.filter(d -> d.getType() == type && Objects.equals(d.getVehicleId(), vehicleId)).findFirst().orElse(null);
		if (document == null || document.getStatus() == DocumentStatus.REJECTED || document.isExpiredOn(today)) {
			byte[] image = fixtureImage(type);
			document = documents.upload(driverId, type, vehicleId, type.requiresExpiry() ? today.plusYears(5) : null,
					image, type.isTwoSided() ? image : null, null);
		}
		if (document.getStatus() == DocumentStatus.PENDING_REVIEW) {
			documents.approve(driverId, document.getId());
		}
	}

	private static byte[] fixtureImage(DocumentType type) {
		BufferedImage image = new BufferedImage(480, 120, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
			graphics.setColor(Color.BLACK);
			graphics.drawString("LOCAL TEST FIXTURE - NOT A REAL DOCUMENT", 15, 35);
			graphics.drawString(type.name(), 15, 65);
		}
		finally {
			graphics.dispose();
		}
		try (var output = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, "png", output)) {
				throw new IllegalStateException("PNG encoder unavailable for local seed fixture");
			}
			return output.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot create local seed fixture image", ex);
		}
	}
}
