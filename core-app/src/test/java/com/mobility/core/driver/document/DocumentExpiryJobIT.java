package com.mobility.core.driver.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.mobility.core.CapturedEvents;
import com.mobility.core.IntegrationTest;
import com.mobility.core.MutableClock;
import com.mobility.core.driver.DriverApi;
import com.mobility.core.driver.DriverApi.Registered;
import com.mobility.core.driver.DriverEvents.DriverDocumentExpiringSoon;
import com.mobility.core.driver.SampleFiles;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.shared.time.BusinessTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

@IntegrationTest
class DocumentExpiryJobIT {

	@Autowired
	RestTestClient client;

	@Autowired
	AccessTokenIssuer issuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DocumentExpiryJob job;

	@Autowired
	CapturedEvents events;

	@Autowired
	MutableClock clock;

	DriverApi api;

	Registered driver;

	@BeforeEach
	void setUp() {
		api = new DriverApi(client, issuer);
		driver = api.register();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	@Test
	void flagsApprovedDocumentsExpiringWithinThirtyDaysInclusive() {
		LocalDate today = BusinessTime.today(clock);
		UUID in29 = approvedDocumentExpiring(today.plusDays(29));
		UUID in30 = approvedDocumentExpiring(today.plusDays(30));
		UUID in31 = approvedDocumentExpiring(today.plusDays(31));

		job.flagExpiringDocuments();

		assertThat(flagged(in29)).isTrue();
		assertThat(flagged(in30)).isTrue();
		assertThat(flagged(in31)).isFalse();
		assertThat(eventFor(in29).expired()).isFalse();
		assertThat(events.ofType(DriverDocumentExpiringSoon.class)).noneMatch(e -> e.documentId().equals(in31));
	}

	@Test
	void alreadyExpiredDocumentsAreFlaggedAsExpired() {
		UUID expired = approvedDocumentExpiring(BusinessTime.today(clock).minusDays(1));

		job.flagExpiringDocuments();

		assertThat(flagged(expired)).isTrue();
		assertThat(eventFor(expired).expired()).isTrue();
	}

	@Test
	void documentsAwaitingReviewAreNotFlagged() {
		UUID pending = api.uploadOk(driver.token(), "DRIVING_LICENSE", SampleFiles.pdf(),
				BusinessTime.today(clock).plusDays(5).toString(), null);

		job.flagExpiringDocuments();

		assertThat(flagged(pending)).isFalse();
	}

	@Test
	void eachDocumentIsFlaggedAndAnnouncedOnlyOnce() {
		UUID doc = approvedDocumentExpiring(BusinessTime.today(clock).plusDays(10));

		job.flagExpiringDocuments();
		Instant firstFlag = flaggedAt(doc);
		clock.advance(Duration.ofDays(1));
		job.flagExpiringDocuments();

		assertThat(flaggedAt(doc)).isEqualTo(firstFlag);
		assertThat(events.ofType(DriverDocumentExpiringSoon.class)).filteredOn(e -> e.documentId().equals(doc)).hasSize(1);
	}

	@Test
	void usesThePhnomPenhDateNotTheUtcDate() {
		// 00:30 in Phnom Penh is still the previous day in UTC.
		Instant now = Instant.now();
		Instant target = now.atOffset(ZoneOffset.UTC).toLocalDate().atTime(17, 30).toInstant(ZoneOffset.UTC);
		if (!target.isAfter(now)) {
			target = target.plus(1, ChronoUnit.DAYS);
		}
		// Create the document before moving the clock (access tokens would expire).
		UUID doc = approvedDocumentExpiring(BusinessTime.today(clock).plusYears(1));
		clock.advance(Duration.between(now, target));
		LocalDate phnomPenhToday = BusinessTime.today(clock);
		assertThat(phnomPenhToday).isAfter(clock.instant().atOffset(ZoneOffset.UTC).toLocalDate());

		// 30 days from the Phnom Penh date = 31 days from the UTC date: must be flagged.
		setExpiry(doc, phnomPenhToday.plusDays(30));

		job.flagExpiringDocuments();

		assertThat(flagged(doc)).isTrue();
	}

	@Test
	void flagIsVisibleToAdmins() {
		UUID doc = approvedDocumentExpiring(BusinessTime.today(clock).plusDays(3));

		job.flagExpiringDocuments();

		assertThat(api.detail(driver.driverId()).get("documents").valueStream()
			.filter(d -> d.get("id").asString().equals(doc.toString()))
			.findFirst().orElseThrow()
			.get("expiryFlaggedAt").isNull()).isFalse();
	}

	/**
	 * Uploads and approves a licence for a new driver, then moves its expiry date (approval refuses
	 * expired documents). A new driver each time, so documents don't supersede each other.
	 */
	private UUID approvedDocumentExpiring(LocalDate expiresOn) {
		Registered owner = api.register();
		UUID doc = api.uploadOk(owner.token(), "DRIVING_LICENSE", SampleFiles.pdf(), DriverApi.inOneYear(), null);
		api.approveDocument(owner.driverId(), doc);
		setExpiry(doc, expiresOn);
		driver = owner;
		return doc;
	}

	private void setExpiry(UUID doc, LocalDate expiresOn) {
		jdbc.update("UPDATE driver.driver_documents SET expires_on = ? WHERE id = ?", java.sql.Date.valueOf(expiresOn), doc);
	}

	private boolean flagged(UUID doc) {
		return flaggedAt(doc) != null;
	}

	private Instant flaggedAt(UUID doc) {
		java.sql.Timestamp ts = jdbc.queryForObject("SELECT expiry_flagged_at FROM driver.driver_documents WHERE id = ?",
				java.sql.Timestamp.class, doc);
		return ts == null ? null : ts.toInstant();
	}

	private DriverDocumentExpiringSoon eventFor(UUID doc) {
		return events.ofType(DriverDocumentExpiringSoon.class)
			.stream()
			.filter(e -> e.documentId().equals(doc))
			.findFirst()
			.orElseThrow();
	}
}
