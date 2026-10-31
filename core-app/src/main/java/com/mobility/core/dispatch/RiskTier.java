package com.mobility.core.dispatch;

/** How likely a trip's driver is to be late: slack = time to pickup − driver's ETA to the pickup. */
public enum RiskTier {
	/** Slack above 15 minutes. */
	GREEN,
	/** Slack 5–15 minutes. */
	AMBER,
	/** Slack under 5 minutes, or no driver position for 3 minutes. */
	RED
}
