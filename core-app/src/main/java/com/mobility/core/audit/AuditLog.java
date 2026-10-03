package com.mobility.core.audit;

import java.util.Map;

/**
 * Records staff/admin actions in {@code audit.audit_logs}. Must be called inside the transaction of
 * the action being audited, so the action and its audit entry commit or roll back together. The
 * actor is taken from the current request's authentication.
 */
public interface AuditLog {

	/**
	 * @param action dotted verb, e.g. {@code driver.suspended}
	 * @param targetType e.g. {@code driver}
	 * @param details extra context; must not contain personal data (ID numbers, bank accounts, ...)
	 */
	void record(String action, String targetType, Object targetId, Map<String, ?> details);

	void record(String action, String targetType, Object targetId);
}
