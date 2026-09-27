package com.mobility.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies Spring Modulith module boundaries: no cycles, and no access to another module's
 * internal (non-API) types. Also writes module diagrams to target/spring-modulith-docs.
 */
class ModularityTests {

	static final ApplicationModules modules = ApplicationModules.of(CoreApplication.class);

	@Test
	void verifiesModuleBoundaries() {
		modules.verify();
	}

	@Test
	void containsAllExpectedModules() {
		assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
			.containsExactlyInAnyOrder("identity", "driver", "corporate", "place", "pricing", "booking",
					"dispatch", "payment", "notification", "safety", "support", "audit", "shared");
	}

	@Test
	void writesDocumentation() {
		new Documenter(modules).writeDocumentation();
	}
}
