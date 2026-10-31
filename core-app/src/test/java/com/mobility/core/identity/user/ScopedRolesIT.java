package com.mobility.core.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.mobility.core.IntegrationTest;
import com.mobility.core.SamplePhones;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.identity.UserAccounts.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ScopedRolesIT {

	@Autowired
	UserAccounts accounts;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void grantsAScopedRoleToANewUserAndReusesExistingUsers() {
		String phone = SamplePhones.next();
		UUID companyA = UUID.randomUUID();
		UUID companyB = UUID.randomUUID();

		UserAccount first = tx.execute(s -> accounts.ensureUserWithScopedRole(phone, "Dara", Role.CORPORATE_ADMIN, companyA));
		UserAccount second = tx.execute(s -> accounts.ensureUserWithScopedRole(phone, "Ignored", Role.CORPORATE_BOOKER, companyB));

		assertThat(second.userId()).isEqualTo(first.userId());
		assertThat(scopes(first.userId())).containsExactlyInAnyOrder("CORPORATE_ADMIN:" + companyA, "CORPORATE_BOOKER:" + companyB);
		assertThat(jdbc.queryForObject("SELECT full_name FROM identity.users WHERE id = ?", String.class, first.userId()))
			.isEqualTo("Dara");
	}

	@Test
	void grantingTwiceIsANoOpAndAuditedOnce() {
		String phone = SamplePhones.next();
		UUID company = UUID.randomUUID();

		UserAccount user = tx.execute(s -> accounts.ensureUserWithScopedRole(phone, "Dara", Role.CORPORATE_BOOKER, company));
		tx.executeWithoutResult(s -> accounts.ensureUserWithScopedRole(phone, "Dara", Role.CORPORATE_BOOKER, company));

		assertThat(scopes(user.userId())).containsExactly("CORPORATE_BOOKER:" + company);
		assertThat(audits(user.userId(), "identity.role.granted")).isEqualTo(1);
	}

	@Test
	void revokesOnlyTheGivenScope() {
		String phone = SamplePhones.next();
		UUID companyA = UUID.randomUUID();
		UUID companyB = UUID.randomUUID();
		UserAccount user = tx.execute(s -> accounts.ensureUserWithScopedRole(phone, "Dara", Role.CORPORATE_BOOKER, companyA));
		tx.executeWithoutResult(s -> accounts.ensureUserWithScopedRole(phone, "Dara", Role.CORPORATE_BOOKER, companyB));

		tx.executeWithoutResult(s -> accounts.revokeScopedRole(user.userId(), Role.CORPORATE_BOOKER, companyA));
		tx.executeWithoutResult(s -> accounts.revokeScopedRole(user.userId(), Role.CORPORATE_BOOKER, companyA));

		assertThat(scopes(user.userId())).containsExactly("CORPORATE_BOOKER:" + companyB);
		assertThat(audits(user.userId(), "identity.role.revoked")).isEqualTo(1);
	}

	@Test
	void onlyCorporateRolesAreScoped() {
		assertThatThrownBy(() -> tx.executeWithoutResult(
				s -> accounts.ensureUserWithScopedRole(SamplePhones.next(), "X", Role.DISPATCHER, UUID.randomUUID())))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> tx.executeWithoutResult(
				s -> accounts.ensureUserWithScopedRole(SamplePhones.next(), "X", Role.CORPORATE_ADMIN, null)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private List<String> scopes(UUID userId) {
		return jdbc.queryForList("SELECT role || ':' || coalesce(scope_id::text, '') FROM identity.user_roles WHERE user_id = ?",
				String.class, userId);
	}

	private int audits(UUID userId, String action) {
		return jdbc.queryForObject("SELECT count(*) FROM audit.audit_logs WHERE target_id = ? AND action = ?", Integer.class,
				userId.toString(), action);
	}
}
