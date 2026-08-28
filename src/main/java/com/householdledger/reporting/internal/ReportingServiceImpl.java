package com.householdledger.reporting.internal;

import com.householdledger.ledger.api.AccountBalanceView;
import com.householdledger.ledger.api.ExpenseTotalView;
import com.householdledger.ledger.api.LedgerReportQueries;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.reporting.api.*;
import com.householdledger.reporting.domain.DateRange;
import com.householdledger.reporting.domain.PresentationSign;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Composes the ledger's aggregates into the reports of PRD §FR-5 and §FR-6.
 *
 * <p>Division of labour: the database does the arithmetic (one query per
 * report, see {@code ledger.internal}), and this class does the shaping —
 * grouping by type, applying the presentation sign of PRD §10, and totalling.
 * No posting is ever loaded into Java to be added up.
 *
 * <p>Package-private, like every other service implementation in the project;
 * Spring instantiates it by component scan and the outside world sees only
 * {@link ReportingService}.
 */
@Service
class ReportingServiceImpl implements ReportingService {

    private final LedgerReportQueries queries;

    ReportingServiceImpl(LedgerReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public BalanceSheet balanceSheet(UUID householdId, LocalDate asOf) {
        Objects.requireNonNull(householdId, "householdId");

        List<AccountBalanceView> balances = queries.accountBalances(householdId, asOf);

        // Bucket by type first so every account type gets a section, even one
        // the household has no accounts of. An absent section and an empty
        // section are indistinguishable to a client otherwise, and the former
        // reads like the report forgot something.
        Map<AccountType, List<AccountBalanceLine>> byType = new EnumMap<>(AccountType.class);
        for (AccountType type : AccountType.values()) {
            byType.put(type, new ArrayList<>());
        }

        long signedTotal = 0L;
        for (AccountBalanceView balance : balances) {
            long signed = balance.signedBalanceMinor();
            byType.get(balance.type()).add(new AccountBalanceLine(
                    balance.accountId(),
                    balance.accountName(),
                    balance.type(),
                    balance.active(),
                    PresentationSign.forDisplay(balance.type(), signed),
                    signed,
                    PresentationSign.isFlipped(balance.type())));

            signedTotal = Math.addExact(signedTotal, signed);
        }

        List<BalanceSheetSection> sections = new ArrayList<>(AccountType.values().length);
        for (AccountType type : AccountType.values()) {
            List<AccountBalanceLine> lines = byType.get(type);

            long presented = 0L;
            long signed = 0L;
            for (AccountBalanceLine line : lines) {
                presented = Math.addExact(presented, line.balanceMinor());
                signed = Math.addExact(signed, line.signedBalanceMinor());
            }

            sections.add(new BalanceSheetSection(type, lines, presented, signed));
        }

        return new BalanceSheet(asOf, sections, signedTotal);
    }

    @Override
    public ExpenseSummary expenseSummary(UUID householdId, DateRange range) {
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(range, "range");

        List<ExpenseTotalView> totals = queries.expenseTotals(householdId, range.from(), range.to());

        List<ExpenseLine> lines = new ArrayList<>(totals.size());
        long total = 0L;
        for (ExpenseTotalView view : totals) {
            // EXPENSE is debit-normal, so the presentation sign is the
            // identity here. Routed through PresentationSign anyway rather
            // than assumed: if the convention for a type ever changed, one
            // place changes, not two.
            long presented = PresentationSign.forDisplay(AccountType.EXPENSE, view.signedTotalMinor());
            lines.add(new ExpenseLine(view.accountId(), view.accountName(), presented));
            total = Math.addExact(total, presented);
        }

        return new ExpenseSummary(range.from(), range.to(), lines, total);
    }

    @Override
    public TrialBalance trialBalance(UUID householdId) {
        Objects.requireNonNull(householdId, "householdId");

        // Three cheap aggregates rather than one clever query: the count is
        // what stops a zero total from being ambiguous (an empty ledger also
        // sums to zero), and the offender list is what makes a failure
        // actionable instead of merely alarming.
        return new TrialBalance(
                queries.trialBalanceMinor(householdId),
                queries.postingCount(householdId),
                queries.findUnbalancedTransactions(householdId));
    }
}
