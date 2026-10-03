package com.mobility.core.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.audit.AuditLog;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.shared.i18n.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Each test rolls back, so removing every ADMIN grant to simulate a fresh environment is safe. */
@IntegrationTest
@Transactional
class AdminBootstrapIT {

	@Autowired
	UserRepository users;

	@Autowired
	UserAccounts accounts;

	@Autowired
	AuditLog auditLog;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void freshEnvironment() {
		jdbc.update("DELETE FROM identity.user_roles WHERE role = 'ADMIN'");
	}

	@Test
	void grantsAdminToANewUserAndAuditsIt() {
		String phone = SamplePhones.next();

		assertThat(bootstrap(phone).bootstrap()).isTrue();

		User admin = users.findByPhoneE164(phone).orElseThrow();
		assertThat(admin.getRoles()).extracting(UserRole::getRole).containsExactly(Role.ADMIN);
		assertThat(admin.getFullName()).isEqualTo("Ops Admin");
		assertThat(jdbc.queryForList("""
				SELECT action FROM audit.audit_logs
				WHERE target_type = 'user' AND target_id = ? AND actor_user_id IS NULL""", String.class,
				admin.getId().toString()))
			.containsExactlyInAnyOrder("identity.role.granted", "identity.admin.bootstrapped");
	}

	@Test
	void promotesAnExistingUserKeepingTheirRoles() {
		String phone = SamplePhones.next();
		users.save(User.registerPassenger(phone, Language.KM));

		assertThat(bootstrap(phone).bootstrap()).isTrue();

		assertThat(users.findByPhoneE164(phone).orElseThrow().getRoles()).extracting(UserRole::getRole)
			.containsExactlyInAnyOrder(Role.PASSENGER, Role.ADMIN);
	}

	@Test
	void doesNothingOnceAnAdminExists() {
		String first = SamplePhones.next();
		String second = SamplePhones.next();
		assertThat(bootstrap(first).bootstrap()).isTrue();

		assertThat(bootstrap(first).bootstrap()).isFalse();
		assertThat(bootstrap(second).bootstrap()).isFalse();

		assertThat(users.findByPhoneE164(second)).isEmpty();
	}

	@Test
	void acceptsLocalPhoneFormats() {
		String e164 = SamplePhones.next();
		String local = "0" + e164.substring(4);

		assertThat(bootstrap(local).bootstrap()).isTrue();

		assertThat(users.findByPhoneE164(e164)).isPresent();
	}

	@Test
	void refusesToStartWithAnInvalidPhone() {
		assertThatThrownBy(() -> bootstrap("not-a-phone").run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("BOOTSTRAP_ADMIN_PHONE");
	}

	@Test
	void unsetPhoneIsANoOp() {
		long before = jdbc.queryForObject("SELECT count(*) FROM identity.user_roles WHERE role = 'ADMIN'", Long.class);

		bootstrap("").run(null);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.user_roles WHERE role = 'ADMIN'", Long.class))
			.isEqualTo(before);
	}

	private AdminBootstrap bootstrap(String phone) {
		return new AdminBootstrap(users, accounts, auditLog, transactions, phone, "Ops Admin");
	}
}
