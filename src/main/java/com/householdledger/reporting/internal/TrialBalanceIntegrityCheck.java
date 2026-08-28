package com.householdledger.reporting.internal;

import com.householdledger.ledger.api.LedgerReportQueries;
import com.householdledger.ledger.api.UnbalancedTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * The daily integrity check of PRD §FR-6: "A scheduled job runs the same
 * check daily and logs an error with affected transaction IDs on failure."
 *
 * <p>Deliberately global rather than per-household. The question it answers
 * is "is the database still internally consistent", which is an operator's
 * question, not a member's — so it does not need, and must not have, an API
 * surface. It also means one query answers it for every household at once,
 * rather than N queries for N households.
 *
 * <p><b>Why this exists at all</b>, given the deferred constraint trigger of
 * PRD §6.3 already makes an unbalanced transaction impossible to commit: a
 * trigger protects writes that go through the database's normal path. It
 * does not protect against a future migration that disables it, a restore
 * from a mangled dump, or a bulk load run with triggers off. The check is
 * cheap and its whole value is being the thing that notices if the guarantee
 * ever stops holding — which is exactly the kind of failure that otherwise
 * goes unnoticed for months.
 *
 * <p>The schedule is configurable via {@code reporting.trial-balance-check.cron}
 * so a deployment can move it off the default without a rebuild;
 * {@code @EnableScheduling} is already set on the application class.
 */
@Component
class TrialBalanceIntegrityCheck {

    private static final Logger log = LoggerFactory.getLogger(TrialBalanceIntegrityCheck.class);

    /** Truncation guard: a catastrophic corruption should not produce a megabyte log line. */
    private static final int MAX_IDS_LOGGED = 50;

    private final LedgerReportQueries queries;

    TrialBalanceIntegrityCheck(LedgerReportQueries queries) {
        this.queries = queries;
    }

    /**
     * Daily at 03:00 by default — after midnight so the previous day's
     * entries are all in, and at an hour when a household is not using the
     * application.
     */
    @Scheduled(cron = "${reporting.trial-balance-check.cron:0 0 3 * * *}")
    void runDailyCheck() {
        verifyIntegrity();
    }

    /**
     * The check itself, separated from the schedule so it can be invoked
     * directly by a test rather than a test having to wait for a cron
     * trigger to fire.
     *
     * @return the transactions found to be unbalanced; empty when healthy
     */
    List<UnbalancedTransaction> verifyIntegrity() {
        List<UnbalancedTransaction> unbalanced = queries.findAllUnbalancedTransactions();

        if (unbalanced.isEmpty()) {
            log.info("Trial balance check passed: every transaction's postings sum to zero");
            return unbalanced;
        }

        // ERROR, not WARN: this means the core invariant of the system (PRD
        // §3.2) has been violated, and every balance and report derived from
        // this data is now suspect.
        log.error("TRIAL BALANCE CHECK FAILED: {} transaction(s) have postings that do not sum to zero. "
                        + "Affected transaction IDs: {}",
                unbalanced.size(), formatAffectedIds(unbalanced));

        return unbalanced;
    }

    /** Renders "id (off by N minor units)" per transaction, truncated if there are many. */
    private String formatAffectedIds(List<UnbalancedTransaction> unbalanced) {
        StringJoiner joiner = new StringJoiner(", ");
        unbalanced.stream()
                .limit(MAX_IDS_LOGGED)
                .forEach(t -> joiner.add(t.transactionId() + " (off by " + t.offByMinor() + " minor units)"));

        if (unbalanced.size() > MAX_IDS_LOGGED) {
            joiner.add("... and " + (unbalanced.size() - MAX_IDS_LOGGED) + " more");
        }

        return joiner.toString();
    }
}
