package com.mobility.core.driver.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.mobility.core.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs V5 against data in the V4 shape, as on staging, in a scratch database. */
@IntegrationTest
class DocumentSidesMigrationIT {

	@Autowired
	DataSource appDataSource;

	@Autowired
	JdbcTemplate appJdbc;

	final String database = "migration_v5_" + UUID.randomUUID().toString().replace("-", "");

	@AfterEach
	void dropScratchDatabase() {
		appJdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
	}

	@Test
	void movesEachFileToTheFrontSideAndDropsInsuranceDocuments() {
		appJdbc.execute("CREATE DATABASE " + database);
		HikariDataSource app = (HikariDataSource) appDataSource;
		String url = app.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		DriverManagerDataSource scratch = new DriverManagerDataSource(url, app.getUsername(), app.getPassword());
		JdbcTemplate jdbc = new JdbcTemplate(scratch);

		flyway(scratch, "4").migrate();
		UUID driver = UUID.randomUUID();
		UUID vehicle = UUID.randomUUID();
		UUID nationalId = UUID.randomUUID();
		UUID insurance = UUID.randomUUID();
		jdbc.update("INSERT INTO driver.drivers (id, user_id, full_name, phone_e164, status) VALUES (?, ?, 'Sok', '+85512345678', 'PENDING')",
				driver, UUID.randomUUID());
		jdbc.update("INSERT INTO driver.vehicles (id, plate_number, vehicle_class, seats) VALUES (?, 'PP 1A-0001', 'CAR', 4)", vehicle);
		jdbc.update("""
				INSERT INTO driver.driver_documents (id, driver_id, type, status, storage_key, content_type, size_bytes, sha256,
				  expires_on, uploaded_at, reviewed_by, reviewed_at, rejection_reason)
				VALUES (?, ?, 'NATIONAL_ID', 'REJECTED', ?, 'image/png', 650011, '\\x01ab'::bytea, '2028-10-06', now(), ?, now(), 'back side missing')""",
				nationalId, driver, "drivers/" + driver + "/" + nationalId, UUID.randomUUID());
		jdbc.update("""
				INSERT INTO driver.driver_documents (id, driver_id, vehicle_id, type, status, storage_key, content_type, size_bytes,
				  sha256, expires_on, uploaded_at)
				VALUES (?, ?, ?, 'VEHICLE_INSURANCE', 'PENDING_REVIEW', ?, 'application/pdf', 100, '\\x02'::bytea, '2028-01-01', now())""",
				insurance, driver, vehicle, "drivers/" + driver + "/" + insurance);

		flyway(scratch, null).migrate();

		Map<String, Object> file = jdbc.queryForMap("SELECT * FROM driver.driver_document_files WHERE document_id = ?", nationalId);
		assertThat(file).containsEntry("side", "FRONT")
			.containsEntry("storage_key", "drivers/" + driver + "/" + nationalId)
			.containsEntry("content_type", "image/png")
			.containsEntry("size_bytes", 650011);
		assertThat((byte[]) file.get("sha256")).containsExactly(0x01, 0xab);
		assertThat(jdbc.queryForObject("SELECT status FROM driver.driver_documents WHERE id = ?", String.class, nationalId))
			.isEqualTo("REJECTED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM driver.driver_documents WHERE id = ?", Integer.class, insurance))
			.isZero();
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO driver.driver_documents (id, driver_id, vehicle_id, type, status, uploaded_at)
				VALUES (?, ?, ?, 'VEHICLE_INSURANCE', 'PENDING_REVIEW', now())""", UUID.randomUUID(), driver, vehicle))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private static Flyway flyway(DataSource dataSource, String target) {
		var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").defaultSchema("public");
		if (target != null) {
			config.target(target);
		}
		return config.load();
	}
}
