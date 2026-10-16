package com.mobility.core.driver.profile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LocalApprovedDriverSeedTest {

	@Test
	void disabledFlagDoesNothingEvenOutsideLocal() {
		var environment = new MockEnvironment().withProperty("spring.profiles.active", "production");
		environment.setActiveProfiles("production");
		assertThatCode(() -> seed(false, environment).run(null)).doesNotThrowAnyException();
	}

	@Test
	void enabledSeedRequiresExplicitLocalProfile() {
		assertThatThrownBy(() -> seed(true, new MockEnvironment()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("requires the local profile");
		var environment = new MockEnvironment();
		environment.setDefaultProfiles("local");
		assertThatThrownBy(() -> seed(true, environment)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void blocksDeploymentProfilesEvenWhenCombinedWithLocal() {
		for (String profile : new String[] { "stage", "staging", "prod", "production", "PRODUCTION" }) {
			var environment = new MockEnvironment();
			environment.setActiveProfiles("local", profile);
			assertThatThrownBy(() -> seed(true, environment))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("forbidden");
		}
	}

	@Test
	void acceptsExplicitLocalOptIn() {
		var environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		assertThatCode(() -> seed(true, environment)).doesNotThrowAnyException();
	}

	private LocalApprovedDriverSeed seed(boolean enabled, MockEnvironment environment) {
		return new LocalApprovedDriverSeed(enabled, environment, null, null, null, null, null, null, null, null, null);
	}
}
