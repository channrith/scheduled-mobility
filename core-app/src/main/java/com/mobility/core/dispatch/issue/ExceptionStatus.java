package com.mobility.core.dispatch.issue;

public enum ExceptionStatus {
	OPEN,
	/** A dispatcher has taken it. */
	ACKNOWLEDGED,
	RESOLVED,
	/** The condition cleared by itself (confirmed, back to GREEN, trip cancelled…). */
	AUTO_RESOLVED;

	public boolean isOpen() {
		return this == OPEN || this == ACKNOWLEDGED;
	}
}
