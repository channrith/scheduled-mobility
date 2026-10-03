package com.mobility.core.identity.user;

import java.util.Map;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.UserAccounts;
import com.mobility.core.identity.UserAccounts.UserAccount;
import com.mobility.core.identity.otp.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Grants ADMIN to {@code identity.bootstrap.admin-phone} at startup, so a fresh environment has someone
 * who can register staff and drivers. Does nothing once any ADMIN exists, so it is safe on every restart
 * and the variable can stay set. The grant is audited with no actor (system).
 */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository users;

	private final UserAccounts accounts;

	private final AuditLog auditLog;

	private final TransactionTemplate transactions;

	private final String phone;

	private final String name;

	AdminBootstrap(UserRepository users, UserAccounts accounts, AuditLog auditLog, TransactionTemplate transactions,
			@Value("${identity.bootstrap.admin-phone:}") String phone,
			@Value("${identity.bootstrap.admin-name:Administrator}") String name) {
		this.users = users;
		this.accounts = accounts;
		this.auditLog = auditLog;
		this.transactions = transactions;
		this.phone = phone;
		this.name = name;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (StringUtils.hasText(phone)) {
			if (PhoneNumbers.normalize(phone).isEmpty()) {
				throw new IllegalStateException("BOOTSTRAP_ADMIN_PHONE is not a valid Cambodian phone number");
			}
			transactions.executeWithoutResult(status -> bootstrap());
		}
	}

	/** Runs in the caller's transaction. @return true if ADMIN was granted */
	boolean bootstrap() {
		if (users.existsByRolesRole(Role.ADMIN)) {
			log.info("Admin bootstrap skipped: an ADMIN already exists");
			return false;
		}
		UserAccount admin = accounts.ensureUserWithRole(phone, name, Role.ADMIN);
		auditLog.record("identity.admin.bootstrapped", "user", admin.userId(), Map.of("source", "startup"));
		log.warn("Granted ADMIN to {} (BOOTSTRAP_ADMIN_PHONE)", PhoneNumbers.mask(admin.phoneE164()));
		return true;
	}
}
