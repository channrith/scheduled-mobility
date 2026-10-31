package com.mobility.core.identity.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.shared.phone.PhoneNumbers;
import com.mobility.core.shared.i18n.Language;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserAccountService implements UserAccounts {

	private final UserRepository users;

	private final AuditLog auditLog;

	UserAccountService(UserRepository users, AuditLog auditLog) {
		this.users = users;
		this.auditLog = auditLog;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UserAccount ensureUserWithRole(String phone, String fullName, Role role) {
		if (role.isCorporate()) {
			throw new IllegalArgumentException("Corporate roles need a scope; not supported here");
		}
		String e164 = PhoneNumbers.normalize(phone)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "phone.invalid"));
		User user = users.findByPhoneE164(e164)
			.orElseGet(() -> users.save(User.createByStaff(e164, fullName, Language.DEFAULT)));
		user.setFullNameIfMissing(fullName);
		if (user.grant(role, null)) {
			auditLog.record("identity.role.granted", "user", user.getId(), Map.of("role", role.name()));
		}
		return new UserAccount(user.getId(), e164);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UserAccount ensureUserWithScopedRole(String phone, String fullName, Role role, UUID scopeId) {
		if (!role.isCorporate() || scopeId == null) {
			throw new IllegalArgumentException("Only corporate roles are scoped, and they need a scope");
		}
		String e164 = PhoneNumbers.normalize(phone)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "phone.invalid"));
		User user = users.findByPhoneE164(e164)
			.orElseGet(() -> users.save(User.createByStaff(e164, fullName, Language.DEFAULT)));
		user.setFullNameIfMissing(fullName);
		if (user.grant(role, scopeId)) {
			auditLog.record("identity.role.granted", "user", user.getId(),
					Map.of("role", role.name(), "scopeId", scopeId));
		}
		return new UserAccount(user.getId(), e164);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void grantRole(UUID userId, Role role, UUID scopeId) {
		if (role.isCorporate() != (scopeId != null)) {
			throw new IllegalArgumentException("Corporate roles need a scope; other roles must not have one");
		}
		User user = users.findWithRolesById(userId)
			.orElseThrow(() -> new IllegalArgumentException("Unknown user " + userId));
		if (user.grant(role, scopeId)) {
			java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
			details.put("role", role.name());
			if (scopeId != null) {
				details.put("scopeId", scopeId);
			}
			auditLog.record("identity.role.granted", "user", userId, details);
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void revokeScopedRole(UUID userId, Role role, UUID scopeId) {
		User user = users.findWithRolesById(userId)
			.orElseThrow(() -> new IllegalArgumentException("Unknown user " + userId));
		if (user.revoke(role, scopeId)) {
			auditLog.record("identity.role.revoked", "user", userId, Map.of("role", role.name(), "scopeId", scopeId));
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void updateProfile(UUID userId, String fullName, Language preferredLanguage) {
		User user = users.findById(userId)
			.orElseThrow(() -> new IllegalArgumentException("Unknown user " + userId));
		List<String> changed = new ArrayList<>();
		if (fullName != null && !fullName.equals(user.getFullName())) {
			user.changeFullName(fullName);
			changed.add("fullName");
		}
		if (preferredLanguage != null && preferredLanguage != user.getPreferredLang()) {
			user.changePreferredLang(preferredLanguage);
			changed.add("preferredLanguage");
		}
		if (!changed.isEmpty()) {
			auditLog.record("identity.profile.updated", "user", userId, Map.of("fields", changed));
		}
	}
}
