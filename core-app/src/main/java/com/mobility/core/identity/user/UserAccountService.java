package com.mobility.core.identity.user;

import java.util.Map;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.identity.otp.PhoneNumbers;
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
}
