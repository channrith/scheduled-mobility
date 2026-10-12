package com.mobility.core.identity.user;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mobility.core.identity.UserDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserDirectoryService implements UserDirectory {

	private final UserRepository users;

	UserDirectoryService(UserRepository users) {
		this.users = users;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, UserSummary> find(Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return users.findAllById(userIds.stream().filter(java.util.Objects::nonNull).distinct().toList())
			.stream()
			.map(u -> new UserSummary(u.getId(), u.getFullName(), u.getPhoneE164(), u.getPreferredLang()))
			.collect(Collectors.toMap(UserSummary::id, Function.identity()));
	}
}
