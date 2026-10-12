package com.mobility.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads audit entries, e.g. everything that happened to one driver. */
public interface AuditTrail {

	record AuditEntry(UUID id, Instant occurredAt, UUID actorUserId, List<String> actorRoles, String action,
			String targetType, String targetId, Map<String, Object> details) {
	}

	/** Matches entries on {@code targetType}/{@code targetId}. */
	record Target(String targetType, String targetId) implements Criterion {
	}

	/** Matches entries on {@code targetType} whose {@code details.<key>} equals {@code value}. */
	record DetailMatch(String targetType, String key, String value) implements Criterion {
	}

	sealed interface Criterion permits Target, DetailMatch {
	}

	/** Entries matching any criterion, newest first. */
	List<AuditEntry> find(List<Criterion> anyOf, int limit);
}
