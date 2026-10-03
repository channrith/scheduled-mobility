package com.mobility.core.identity.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.identity.Role;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "The signed-in user")
class MeController {

	private final CurrentUserProvider currentUser;

	private final UserRepository users;

	MeController(CurrentUserProvider currentUser, UserRepository users) {
		this.currentUser = currentUser;
		this.users = users;
	}

	/** Reads the profile from the database, so it reflects role changes made since the token was issued. */
	@Operation(summary = "Current user's profile and roles", description = "Read from the database, so it includes role changes made after the token was issued.")
	@GetMapping
	@Transactional(readOnly = true)
	Me me() {
		User user = users.findWithRolesById(currentUser.require().userId())
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "auth.unauthorized"));
		List<RoleGrant> roles = user.getRoles()
			.stream()
			.map(r -> new RoleGrant(r.getRole(), r.getScopeId()))
			.sorted(Comparator.comparing(RoleGrant::role))
			.toList();
		return new Me(user.getId(), user.getPhoneE164(), user.getFullName(), user.getPreferredLang().code(),
				user.getStatus(), roles);
	}

	record Me(UUID id, String phone, String fullName, String preferredLang, UserStatus status, List<RoleGrant> roles) {
	}

	record RoleGrant(Role role, UUID scopeId) {
	}
}
