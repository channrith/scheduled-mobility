package com.mobility.core.dispatch;

import com.mobility.core.dispatch.assignment.DeadlineWatcher;
import com.mobility.core.dispatch.risk.RiskEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every 30 s: risk tiers, then confirmation deadlines and unassigned trips. A Postgres advisory lock lets only one
 * core-app instance run them at a time; the others skip that round. Off in tests (they call the components).
 */
@Component
class DispatchJobs {

	private static final Logger log = LoggerFactory.getLogger(DispatchJobs.class);

	private final RiskEvaluator risk;

	private final DeadlineWatcher deadlines;

	private final TransactionTemplate transactions;

	private final JdbcTemplate jdbc;

	DispatchJobs(RiskEvaluator risk, DeadlineWatcher deadlines, TransactionTemplate transactions, JdbcTemplate jdbc) {
		this.risk = risk;
		this.deadlines = deadlines;
		this.transactions = transactions;
		this.jdbc = jdbc;
	}

	@Scheduled(fixedDelayString = "${dispatch.evaluation-interval:30s}")
	void run() {
		try {
			transactions.executeWithoutResult(s -> {
				Boolean mine = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('dispatch.jobs', 0))",
						Boolean.class);
				if (Boolean.TRUE.equals(mine)) {
					risk.evaluate();
					deadlines.check();
				}
			});
		}
		catch (RuntimeException e) {
			log.warn("Dispatch evaluation failed: {}", e.toString());
		}
	}
}
