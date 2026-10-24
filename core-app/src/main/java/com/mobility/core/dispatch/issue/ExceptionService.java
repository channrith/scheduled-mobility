package com.mobility.core.dispatch.issue;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.dispatch.DispatchEvents.DispatchExceptionOpened;
import com.mobility.core.shared.outbox.Outbox;
import com.mobility.core.shared.time.StoredTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The exception queue: one open exception per trip and type, refreshed while the condition lasts. */
@Service
public class ExceptionService {

	static final int MIN_NOTE = 5;

	private final ExceptionCaseRepository exceptions;

	private final Outbox outbox;

	private final AuditLog auditLog;

	private final Clock clock;

	ExceptionService(ExceptionCaseRepository exceptions, Outbox outbox, AuditLog auditLog, Clock clock) {
		this.exceptions = exceptions;
		this.outbox = outbox;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** Opens the exception, or updates the open one of that trip and type (severity, driver, details). */
	@Transactional
	public ExceptionCase raise(ExceptionType type, UUID tripId, UUID driverId, Severity severity, Instant pickupAt,
			Map<String, Object> details) {
		Instant now = now();
		var open = exceptions.findOpen(tripId, type);
		if (open.isPresent()) {
			ExceptionCase existing = open.get();
			if (existing.refresh(driverId, severity, details, now)) {
				exceptions.save(existing);
			}
			return existing;
		}
		ExceptionCase created = exceptions.saveAndFlush(new ExceptionCase(type, tripId, driverId, severity, pickupAt, details, now));
		outbox.publish("dispatch.exception-opened", "dispatch_exception", created.getId(),
				new DispatchExceptionOpened(created.getId(), type.name(), severity.name(), tripId, pickupAt));
		return created;
	}

	/** Closes the open exceptions of these types for the trip (all types when none are given). */
	@Transactional
	public int autoResolve(UUID tripId, ExceptionType... types) {
		Collection<ExceptionType> which = types.length == 0 ? Arrays.asList(ExceptionType.values()) : Arrays.asList(types);
		Instant now = now();
		List<ExceptionCase> open = exceptions.findOpen(tripId, which);
		open.forEach(e -> e.close(ExceptionStatus.AUTO_RESOLVED, null, null, now));
		exceptions.saveAll(open);
		return open.size();
	}

	/** Severity first, then the soonest pickup. Default: OPEN and ACKNOWLEDGED. */
	@Transactional(readOnly = true)
	public List<ExceptionCase> queue(Collection<ExceptionStatus> statuses) {
		return exceptions.queue(statuses == null || statuses.isEmpty() ? List.of(ExceptionStatus.OPEN, ExceptionStatus.ACKNOWLEDGED)
				: statuses);
	}

	@Transactional
	public ExceptionCase take(UUID id, long expectedVersion, UUID dispatcherId) {
		ExceptionCase e = open(id, expectedVersion);
		e.take(dispatcherId, now());
		ExceptionCase saved = exceptions.saveAndFlush(e);
		auditLog.record("dispatch.exception.taken", "dispatch_exception", id, Map.of("type", e.getType().name()));
		return saved;
	}

	@Transactional
	public ExceptionCase resolve(UUID id, long expectedVersion, String note, UUID dispatcherId) {
		String n = note == null ? "" : note.strip();
		if (n.length() < MIN_NOTE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "dispatch.note-required", MIN_NOTE);
		}
		ExceptionCase e = open(id, expectedVersion);
		e.close(ExceptionStatus.RESOLVED, dispatcherId, n.length() > 500 ? n.substring(0, 500) : n, now());
		ExceptionCase saved = exceptions.saveAndFlush(e);
		auditLog.record("dispatch.exception.resolved", "dispatch_exception", id, Map.of("type", e.getType().name()));
		return saved;
	}

	private ExceptionCase open(UUID id, long expectedVersion) {
		ExceptionCase e = exceptions.findById(id)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "dispatch.exception-not-found"));
		if (!Objects.equals(e.getVersion(), expectedVersion)) {
			throw new ApiException(HttpStatus.CONFLICT, "concurrent-update");
		}
		if (!e.getStatus().isOpen()) {
			throw new ApiException(HttpStatus.CONFLICT, "dispatch.exception-closed");
		}
		return e;
	}

	private Instant now() {
		return StoredTime.of(clock.instant());
	}
}
