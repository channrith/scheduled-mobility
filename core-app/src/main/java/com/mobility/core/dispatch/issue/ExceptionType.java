package com.mobility.core.dispatch.issue;

public enum ExceptionType {
	/** No driver 2 hours before pickup. */
	UNASSIGNED,
	/** The primary driver did not confirm by the deadline. */
	UNCONFIRMED,
	BACKUP_UNCONFIRMED,
	/** An assigned driver (primary or backup) declined. */
	DRIVER_DECLINED,
	/** AMBER (HIGH) or RED (CRITICAL) risk tier. */
	AT_RISK,
	/** An airport or corporate ride without a backup driver. */
	NO_BACKUP
}
