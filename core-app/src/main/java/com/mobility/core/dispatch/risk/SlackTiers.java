package com.mobility.core.dispatch.risk;

import java.time.Duration;
import java.time.Instant;

import com.mobility.core.dispatch.DispatchProperties.Risk;
import com.mobility.core.dispatch.RiskTier;

/** Pure tiering of one trip; unit-tested at every boundary. */
public final class SlackTiers {

	public enum Reason {
		/** Tiered by slack. */
		SLACK,
		/** The driver sent no position recently enough. */
		NO_PING
	}

	/** @param slack null when there was no usable position */
	public record Evaluation(RiskTier tier, Duration slack, Reason reason) {
	}

	private SlackTiers() {
	}

	/**
	 * @param lastPingAt null when the driver never sent a position
	 * @param eta the driver's estimated time to the pickup (ignored without a fresh position)
	 */
	public static Evaluation evaluate(Instant now, Instant pickupAt, Instant lastPingAt, Duration eta, Risk risk) {
		if (lastPingAt == null || Duration.between(lastPingAt, now).compareTo(risk.pingStale()) >= 0) {
			return new Evaluation(RiskTier.RED, null, Reason.NO_PING);
		}
		Duration slack = Duration.between(now, pickupAt).minus(eta);
		return new Evaluation(tier(slack, risk), slack, Reason.SLACK);
	}

	/** GREEN above 15 min, AMBER from 5 to 15 min (both included), RED below 5 min. */
	public static RiskTier tier(Duration slack, Risk risk) {
		if (slack.compareTo(risk.greenAbove()) > 0) {
			return RiskTier.GREEN;
		}
		return slack.compareTo(risk.amberAbove()) >= 0 ? RiskTier.AMBER : RiskTier.RED;
	}
}
