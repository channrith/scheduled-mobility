package com.mobility.core.identity;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the {@link CurrentUser} of the current request from its access token. */
@Component
public class CurrentUserProvider {

	public Optional<CurrentUser> current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwtAuth) {
			return Optional.of(fromJwt(jwtAuth.getToken()));
		}
		return Optional.empty();
	}

	/** @throws ApiException 401 when the request is not authenticated */
	public CurrentUser require() {
		return current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "auth.unauthorized"));
	}

	static CurrentUser fromJwt(Jwt jwt) {
		Set<Role> roles = toRoles(jwt.getClaimAsStringList("roles"));
		Map<UUID, Set<Role>> corporate = new HashMap<>();
		Map<String, Object> corp = jwt.hasClaim("corp") ? jwt.getClaimAsMap("corp") : Map.of();
		corp.forEach((corporateId, corporateRoles) -> corporate.put(UUID.fromString(corporateId),
				corporateRoles instanceof Collection<?> c ? toRoles(c.stream().map(String::valueOf).toList()) : Set.of()));
		String lang = jwt.getClaimAsString("lang");
		return new CurrentUser(UUID.fromString(jwt.getSubject()), roles, corporate,
				lang == null ? Language.DEFAULT : Language.fromCode(lang));
	}

	/** Unknown role names (e.g. from a newer deployment) are ignored rather than failing the request. */
	private static Set<Role> toRoles(List<String> names) {
		Set<Role> roles = EnumSet.noneOf(Role.class);
		if (names != null) {
			for (String name : names) {
				try {
					roles.add(Role.valueOf(name));
				}
				catch (IllegalArgumentException ignored) {
					// skip
				}
			}
		}
		return roles;
	}
}
