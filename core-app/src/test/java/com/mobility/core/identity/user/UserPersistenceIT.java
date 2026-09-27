package com.mobility.core.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.Role;
import com.mobility.core.shared.i18n.Language;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class UserPersistenceIT {

	@Autowired
	UserRepository users;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void persistsUserWithRoles() {
		UUID corporateId = UUID.randomUUID();
		User user = User.registerPassenger(randomPhone(), Language.KM);
		user.grant(Role.CORPORATE_BOOKER, corporateId);
		users.save(user);

		User loaded = users.findByPhoneE164(user.getPhoneE164()).orElseThrow();

		assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(loaded.getPreferredLang()).isEqualTo(Language.KM);
		assertThat(loaded.getRoles()).extracting(UserRole::getRole, UserRole::getScopeId)
			.containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(Role.PASSENGER, null),
					org.assertj.core.groups.Tuple.tuple(Role.CORPORATE_BOOKER, corporateId));
	}

	@Test
	void preferredLanguageDefaultsToKhmer() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO identity.users (id, phone_e164) VALUES (?, ?)", id, randomPhone());

		assertThat(jdbc.queryForObject("SELECT preferred_lang FROM identity.users WHERE id = ?", String.class, id))
			.isEqualTo("km");
	}

	@Test
	void phoneNumberIsUnique() {
		String phone = randomPhone();
		users.saveAndFlush(User.registerPassenger(phone, Language.KM));

		assertThatThrownBy(() -> users.saveAndFlush(User.registerPassenger(phone, Language.EN)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void corporateRoleRequiresScopeAndGlobalRoleForbidsIt() {
		UUID userId = insertUser();

		assertThatThrownBy(() -> insertRole(userId, "CORPORATE_ADMIN", null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertRole(userId, "DISPATCHER", UUID.randomUUID()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateGlobalRoleIsRejectedEvenWithNullScope() {
		UUID userId = insertUser();
		insertRole(userId, "DISPATCHER", null);

		assertThatThrownBy(() -> insertRole(userId, "DISPATCHER", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertUser() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO identity.users (id, phone_e164) VALUES (?, ?)", id, randomPhone());
		return id;
	}

	private void insertRole(UUID userId, String role, UUID scopeId) {
		jdbc.update("INSERT INTO identity.user_roles (id, user_id, role, scope_id) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), userId, role, scopeId);
	}

	static String randomPhone() {
		return "+85512" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
	}
}
