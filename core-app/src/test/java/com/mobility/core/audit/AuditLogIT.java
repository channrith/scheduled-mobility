package com.mobility.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class AuditLogIT {

	@Autowired
	AuditLog auditLog;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void recordsActorRolesAndDetails() {
		UUID actor = UUID.randomUUID();
		String target = UUID.randomUUID().toString();
		authenticate(actor, "ROLE_ADMIN", "ROLE_SUPPORT", "FACTOR_BEARER");

		tx.executeWithoutResult(s -> auditLog.record("test.happened", "thing", target, Map.of("reason", "because")));

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT actor_user_id, array_to_string(actor_roles, ',') AS roles, action, details->>'reason' AS reason "
						+ "FROM audit.audit_logs WHERE target_id = ?", target);
		assertThat(row).containsEntry("actor_user_id", actor)
			.containsEntry("roles", "ADMIN,SUPPORT")
			.containsEntry("action", "test.happened")
			.containsEntry("reason", "because");
	}

	@Test
	void rollsBackWithTheAuditedAction() {
		String target = UUID.randomUUID().toString();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			auditLog.record("test.happened", "thing", target);
			throw new IllegalStateException("action failed");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(count(target)).isZero();
	}

	@Test
	void refusesToWriteOutsideATransaction() {
		assertThatThrownBy(() -> auditLog.record("test.happened", "thing", "x"))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> auditLog.record("test.happened", "thing", "x", Map.of()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void entriesCannotBeModifiedOrDeleted() {
		String target = UUID.randomUUID().toString();
		tx.executeWithoutResult(s -> auditLog.record("test.happened", "thing", target));

		assertThatThrownBy(() -> jdbc.update("UPDATE audit.audit_logs SET action = 'x' WHERE target_id = ?", target))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit.audit_logs WHERE target_id = ?", target))
			.hasMessageContaining("append-only");
		assertThat(count(target)).isEqualTo(1);
	}

	private int count(String target) {
		return jdbc.queryForObject("SELECT count(*) FROM audit.audit_logs WHERE target_id = ?", Integer.class, target);
	}

	private static void authenticate(UUID userId, String... authorities) {
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null,
					List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
	}
}
