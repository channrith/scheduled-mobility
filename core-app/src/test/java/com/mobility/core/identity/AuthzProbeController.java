package com.mobility.core.identity;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints exercising the method-security helpers over real HTTP. */
@RestController
@RequestMapping("/api/v1/test/authz")
class AuthzProbeController {

	@GetMapping("/dispatch")
	@PreAuthorize("hasRole('DISPATCHER')")
	String dispatchOnly() {
		return "ok";
	}

	@GetMapping("/corporates/{corporateId}")
	@PreAuthorize("@authz.canAccessCorporate(#corporateId)")
	String corporate(@PathVariable UUID corporateId) {
		return "ok";
	}

	@GetMapping("/corporates/{corporateId}/settings")
	@PreAuthorize("@authz.canManageCorporate(#corporateId)")
	String manageCorporate(@PathVariable UUID corporateId) {
		return "ok";
	}
}
