package com.householdledger.ledger.internal;

import com.householdledger.ledger.api.AccountBalanceView;
import com.householdledger.ledger.api.ExpenseTotalView;
import com.householdledger.ledger.api.LedgerReportQueries;
import com.householdledger.ledger.api.UnbalancedTransaction;
import com.householdledger.ledger.domain.AccountType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Native SQL aggregation for the reporting queries (PRD §FR-5, §FR-6).
 *
 * <p><b>Why native SQL rather than JPQL or in-memory summing.</b> These are
 * pure set operations: group the postings, add them up, hand back one row per
 * account. PRD §5 targets p95 under 200ms at 10k postings, and the way to
 * miss that is to load ten thousand rows into Java to add them. Doing the
 * arithmetic in the database keeps each report to a single round trip and a
 * result set the size of the chart of accounts — a dozen rows, not ten
 * thousand. The balance-sheet query in particular needs a {@code LEFT JOIN}
 * with a conditional sum so that accounts with no qualifying postings still
 * appear at zero, which is clumsy to express in JPQL and exact in SQL.
 *
 * <p>All of it stays behind {@code ledger.internal}, so the SQL never leaks
 * into a module contract.
 *
 * <p><b>Every aggregate is CAST to BIGINT.</b> PostgreSQL's {@code SUM} over
 * a {@code bigint} column returns {@code numeric}, which arrives as a
 * {@code BigDecimal} over JDBC; without the cast every one of these mappings
 * would fail at runtime with a class-cast, and it would fail only against a
 * real database. Money stays a {@code long} of minor units end to end
 * (PRD §3.3).
 *
 * <p><b>No index was added for these queries.</b> The existing
 * {@code idx_posting_account}, {@code idx_posting_txn},
 * {@code idx_txn_household_date} and the {@code (household_id, name)} unique
 * index on {@code account} already cover every access path here. At the
 * PRD's stated scale the planner correctly prefers a sequential scan and
 * hash aggregate over index lookups, so a speculative index would add write
 * cost for no read benefit.
 */
@Repository
class LedgerReportQueriesImpl implements LedgerReportQueries {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * One row per account, with a conditional sum so the date bound does not
     * turn the {@code LEFT JOIN} into an inner one.
     *
     * <p>The subtlety: putting {@code occurred_on <= :asOf} in the
     * {@code WHERE} clause would discard accounts whose only postings fall
     * after the cut-off, so they would vanish from the balance sheet instead
     * of showing zero. Putting it inside the {@code SUM} keeps every account
     * in the result and contributes only the qualifying postings. The
     * {@code :asOf IS NULL} branch makes an unbounded balance sheet the same
     * query rather than a second one.
     */
    private static final String ACCOUNT_BALANCES_SQL = """
            SELECT a.id,
                   a.name,
                   a.type,
                   a.is_active,
                   CAST(COALESCE(SUM(CASE
                       WHEN CAST(:asOf AS date) IS NULL OR t.occurred_on <= CAST(:asOf AS date)
                       THEN p.amount_minor ELSE 0 END), 0) AS BIGINT) AS balance_minor
            FROM account a
            LEFT JOIN posting p ON p.account_id = a.id
            LEFT JOIN ledger_transaction t ON t.id = p.transaction_id
            WHERE a.household_id = :householdId
            GROUP BY a.id, a.name, a.type, a.is_active
            ORDER BY a.type, a.name
            """;

    /**
     * Inner joins throughout: a category with no spending in the range is
     * absent from the summary rather than present at zero.
     *
     * <p>Grouping by {@code a.id} means a transaction posting twice to the
     * same category contributes both legs once each — the join cannot
     * duplicate a posting row, because a posting joins to exactly one account
     * and one transaction.
     */
    private static final String EXPENSE_TOTALS_SQL = """
            SELECT a.id,
                   a.name,
                   CAST(COALESCE(SUM(p.amount_minor), 0) AS BIGINT) AS total_minor
            FROM account a
            JOIN posting p ON p.account_id = a.id
            JOIN ledger_transaction t ON t.id = p.transaction_id
            WHERE a.household_id = :householdId
              AND a.type = 'EXPENSE'
              AND t.occurred_on >= :fromDate
              AND t.occurred_on <= :toDate
            GROUP BY a.id, a.name
            ORDER BY total_minor DESC, a.name
            """;

    /** Joins through the transaction because household lives there, not on the posting. */
    private static final String TRIAL_BALANCE_SQL = """
            SELECT CAST(COALESCE(SUM(p.amount_minor), 0) AS BIGINT)
            FROM posting p
            JOIN ledger_transaction t ON t.id = p.transaction_id
            WHERE t.household_id = :householdId
            """;

    private static final String POSTING_COUNT_SQL = """
            SELECT CAST(COUNT(*) AS BIGINT)
            FROM posting p
            JOIN ledger_transaction t ON t.id = p.transaction_id
            WHERE t.household_id = :householdId
            """;

    /**
     * The integrity check, in two forms rather than one with a nullable
     * parameter.
     *
     * <p>The single-statement version read
     * {@code WHERE (:householdId IS NULL OR t.household_id = CAST(:householdId AS uuid))},
     * and PostgreSQL rejected it with <i>"could not determine data type of
     * parameter $1"</i>. Hibernate expands the named parameter into two
     * positional ones, and the first appears only as {@code ? IS NULL} —
     * a position that gives the planner nothing to infer a type from. The
     * cast on the second occurrence does not help the first.
     *
     * <p>Casting both occurrences fixes the type inference and was verified
     * to work. Two dedicated statements were chosen instead because they
     * remove the nullable parameter altogether: there is no null left to
     * bind, so no type left to infer, and each query says plainly what it
     * selects. The semantics are unchanged — no household means every
     * household — and both statements share the same {@code HAVING} clause,
     * so the scheduled check and the per-household endpoint still apply one
     * definition of "unbalanced".
     */
    private static final String UNBALANCED_COLUMNS = """
            SELECT t.id, CAST(SUM(p.amount_minor) AS BIGINT) AS off_by
            FROM ledger_transaction t
            JOIN posting p ON p.transaction_id = t.id
            """;

    private static final String UNBALANCED_GROUPING = """
            GROUP BY t.id
            HAVING SUM(p.amount_minor) <> 0
            """;

    /** Every household — the daily operator-facing integrity check (PRD §FR-6). */
    private static final String UNBALANCED_ALL_SQL = UNBALANCED_COLUMNS + UNBALANCED_GROUPING;

    /** One household — what the trial-balance endpoint reports on. */
    private static final String UNBALANCED_IN_HOUSEHOLD_SQL =
            UNBALANCED_COLUMNS + "WHERE t.household_id = :householdId\n" + UNBALANCED_GROUPING;

    @Override
    @Transactional(readOnly = true)
    public List<AccountBalanceView> accountBalances(UUID householdId, LocalDate asOf) {
        Query query = entityManager.createNativeQuery(ACCOUNT_BALANCES_SQL)
                .setParameter("householdId", householdId)
                .setParameter("asOf", asOf);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return rows.stream()
                .map(row -> new AccountBalanceView(
                        (UUID) row[0],
                        (String) row[1],
                        AccountType.valueOf((String) row[2]),
                        (Boolean) row[3],
                        ((Number) row[4]).longValue()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseTotalView> expenseTotals(UUID householdId, LocalDate from, LocalDate to) {
        Query query = entityManager.createNativeQuery(EXPENSE_TOTALS_SQL)
                .setParameter("householdId", householdId)
                .setParameter("fromDate", from)
                .setParameter("toDate", to);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return rows.stream()
                .map(row -> new ExpenseTotalView(
                        (UUID) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long trialBalanceMinor(UUID householdId) {
        Object result = entityManager.createNativeQuery(TRIAL_BALANCE_SQL)
                .setParameter("householdId", householdId)
                .getSingleResult();

        return ((Number) result).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    public long postingCount(UUID householdId) {
        Object result = entityManager.createNativeQuery(POSTING_COUNT_SQL)
                .setParameter("householdId", householdId)
                .getSingleResult();

        return ((Number) result).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnbalancedTransaction> findUnbalancedTransactions(UUID householdId) {
        Objects.requireNonNull(householdId, "householdId");

        return toUnbalanced(entityManager.createNativeQuery(UNBALANCED_IN_HOUSEHOLD_SQL)
                .setParameter("householdId", householdId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnbalancedTransaction> findAllUnbalancedTransactions() {
        return toUnbalanced(entityManager.createNativeQuery(UNBALANCED_ALL_SQL));
    }

    private List<UnbalancedTransaction> toUnbalanced(Query query) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        return rows.stream()
                .map(row -> new UnbalancedTransaction((UUID) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}
