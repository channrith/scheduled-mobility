package com.mobility.core.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.shared.i18n.Language;

/** Read-only lookups of user profiles for other modules (e.g. showing who did something). */
public interface UserDirectory {

	record UserSummary(UUID id, String fullName, String phoneE164, Language preferredLanguage) {
	}

	/** Unknown ids are left out of the result. */
	Map<UUID, UserSummary> find(Collection<UUID> userIds);
}
