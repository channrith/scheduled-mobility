package com.mobility.core.pricing.web;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.identity.UserDirectory;
import com.mobility.core.identity.UserDirectory.UserSummary;
import org.springframework.stereotype.Component;

/** "Who changed it": names (or phone numbers) of staff, looked up once per response. */
@Component
class PersonViews {

	record PersonView(UUID id, String name) {
	}

	private final UserDirectory users;

	PersonViews(UserDirectory users) {
		this.users = users;
	}

	Map<UUID, UserSummary> lookup(Collection<UUID> ids) {
		return users.find(ids.stream().filter(Objects::nonNull).distinct().toList());
	}

	static PersonView person(UUID id, Map<UUID, UserSummary> people) {
		if (id == null) {
			return null;
		}
		UserSummary u = people.get(id);
		String name = u == null ? null : u.fullName() != null && !u.fullName().isBlank() ? u.fullName() : u.phoneE164();
		return new PersonView(id, name);
	}
}
