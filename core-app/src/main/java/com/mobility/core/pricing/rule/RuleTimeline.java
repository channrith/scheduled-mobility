package com.mobility.core.pricing.rule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which version of one price line is in force when. At time t the version with the latest valid_from ≤ t
 * wins (ties: the one created last); it applies only if it is active and t is before its valid_to. An older
 * version never comes back once a later one has started.
 */
public final class RuleTimeline {

	public enum Period {
		/** Starts later. */
		UPCOMING,
		/** In force now. */
		CURRENT,
		/** Was in force, replaced or ended. */
		PAST,
		/** Replaced by a version created later with the same start: never in force. */
		NEVER_IN_FORCE,
		/** A version that ends the line (no price from its start). */
		RETIREMENT
	}

	/** @param effectiveUntil end of the period it is (or will be) in force: its valid_to or the next version's start */
	public record Entry(PricingRule rule, Instant effectiveUntil, Period period) {
	}

	/** Newest created last; a version not yet read back has no seq and is the newest. */
	static final Comparator<PricingRule> ORDER = Comparator.comparing(PricingRule::getValidFrom)
		.thenComparing(r -> r.getSeq() == null ? Long.MAX_VALUE : r.getSeq());

	private final List<PricingRule> versions;

	RuleTimeline(List<PricingRule> versions) {
		this.versions = versions.stream().sorted(ORDER).toList();
	}

	/** The version in force at {@code at}, if any. */
	Optional<PricingRule> at(Instant at) {
		PricingRule latest = null;
		for (PricingRule r : versions) {
			if (r.getValidFrom().isAfter(at)) {
				break;
			}
			latest = r;
		}
		return latest != null && latest.appliesAt(at) ? Optional.of(latest) : Optional.empty();
	}

	/** Versions starting after {@code now} that will be in force (oldest first). */
	List<PricingRule> upcoming(Instant now) {
		return entries(now).stream().filter(e -> e.period() == Period.UPCOMING).map(Entry::rule).toList();
	}

	/** Every version, newest-starting first, with its period relative to {@code now}. */
	List<Entry> entries(Instant now) {
		Optional<PricingRule> current = at(now);
		List<Entry> result = new ArrayList<>();
		for (int i = 0; i < versions.size(); i++) {
			PricingRule r = versions.get(i);
			Instant nextStart = null;
			boolean replacedAtStart = false;
			for (int j = i + 1; j < versions.size(); j++) {
				if (versions.get(j).getValidFrom().equals(r.getValidFrom())) {
					replacedAtStart = true;
				}
				else {
					nextStart = versions.get(j).getValidFrom();
					break;
				}
			}
			Instant until = r.getValidTo() == null ? nextStart
					: nextStart == null || r.getValidTo().isBefore(nextStart) ? r.getValidTo() : nextStart;
			Period period;
			if (replacedAtStart) {
				period = Period.NEVER_IN_FORCE;
			}
			else if (!r.isActive()) {
				period = Period.RETIREMENT;
			}
			else if (r.getValidFrom().isAfter(now)) {
				period = Period.UPCOMING;
			}
			else if (current.isPresent() && current.get() == r) {
				period = Period.CURRENT;
			}
			else {
				period = Period.PAST;
			}
			result.add(new Entry(r, replacedAtStart ? r.getValidFrom() : until, period));
		}
		return result.reversed();
	}
}
