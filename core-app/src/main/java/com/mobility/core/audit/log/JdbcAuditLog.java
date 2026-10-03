package com.mobility.core.audit.log;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** MANDATORY on the class so every entry point (no self-invocation bypass) requires a caller transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JdbcAuditLog implements AuditLog {

	private final JdbcTemplate jdbc;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	JdbcAuditLog(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	@Override
	public void record(String action, String targetType, Object targetId) {
		record(action, targetType, targetId, Map.of());
	}

	@Override
	public void record(String action, String targetType, Object targetId, Map<String, ?> details) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		UUID actor = null;
		String[] roles = new String[0];
		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			actor = parseUuid(auth.getName());
			roles = auth.getAuthorities()
				.stream()
				.map(GrantedAuthority::getAuthority)
				.filter(a -> a.startsWith("ROLE_"))
				.map(a -> a.substring("ROLE_".length()))
				.sorted()
				.toArray(String[]::new);
		}
		jdbc.update("""
				INSERT INTO audit.audit_logs (id, occurred_at, actor_user_id, actor_roles, action, target_type, target_id, details)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)""", UUID.randomUUID(), Timestamp.from(clock.instant()), actor,
				roles, action, targetType, String.valueOf(targetId), jsonMapper.writeValueAsString(details));
	}

	private static UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
