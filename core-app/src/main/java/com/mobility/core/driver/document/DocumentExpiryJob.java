package com.mobility.core.driver.document;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.mobility.core.driver.DriverEvents.DriverDocumentExpiringSoon;
import com.mobility.core.shared.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily: flags approved documents that expire within the warning window (or already expired) and
 * publishes {@link DriverDocumentExpiringSoon} once per document. The conditional UPDATE makes it safe to
 * run twice or on several instances at once: each document is claimed by exactly one run.
 */
@Component
class DocumentExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(DocumentExpiryJob.class);

	private final JdbcTemplate jdbc;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final int warningDays;

	DocumentExpiryJob(JdbcTemplate jdbc, ApplicationEventPublisher events, Clock clock,
			@Value("${driver.documents.expiry-warning-days:30}") int warningDays) {
		this.jdbc = jdbc;
		this.events = events;
		this.clock = clock;
		this.warningDays = warningDays;
	}

	record Flagged(UUID documentId, UUID driverId, String type, LocalDate expiresOn) {
	}

	@Scheduled(cron = "${driver.documents.expiry-check-cron:0 0 6 * * *}", zone = "Asia/Phnom_Penh")
	@Transactional
	public int flagExpiringDocuments() {
		LocalDate today = BusinessTime.today(clock);
		List<Flagged> flagged = jdbc.query("""
				UPDATE driver.driver_documents
				   SET expiry_flagged_at = ?, version = version + 1
				 WHERE status = 'APPROVED'
				   AND expires_on IS NOT NULL
				   AND expires_on <= ?
				   AND expiry_flagged_at IS NULL
				RETURNING id, driver_id, type, expires_on""",
				(rs, i) -> new Flagged(rs.getObject("id", UUID.class), rs.getObject("driver_id", UUID.class),
						rs.getString("type"), rs.getDate("expires_on").toLocalDate()),
				Timestamp.from(clock.instant()), Date.valueOf(today.plusDays(warningDays)));
		for (Flagged doc : flagged) {
			events.publishEvent(new DriverDocumentExpiringSoon(doc.driverId(), doc.documentId(), doc.type(),
					doc.expiresOn(), doc.expiresOn().isBefore(today)));
		}
		if (!flagged.isEmpty()) {
			log.info("Flagged {} driver document(s) expiring on or before {}", flagged.size(), today.plusDays(warningDays));
		}
		return flagged.size();
	}
}
