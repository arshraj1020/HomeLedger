package com.householdledger.web.ui.support;

import com.householdledger.ledger.api.PageResult;
import com.householdledger.ledger.api.PostingDetail;
import com.householdledger.ledger.api.TransactionDetail;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.reporting.api.AccountBalanceLine;
import com.householdledger.reporting.api.BalanceSheet;
import com.householdledger.reporting.api.BalanceSheetSection;
import com.householdledger.reporting.api.ExpenseSummary;
import com.householdledger.reporting.api.TrialBalance;
import com.householdledger.web.ui.view.AccountOption;
import com.householdledger.web.ui.view.AccountOptionGroup;
import com.householdledger.web.ui.view.AccountRow;
import com.householdledger.web.ui.view.AccountSection;
import com.householdledger.web.ui.view.ExpenseRow;
import com.householdledger.web.ui.view.PageBar;
import com.householdledger.web.ui.view.PostingRow;
import com.householdledger.web.ui.view.TransactionDetailView;
import com.householdledger.web.ui.view.TransactionRow;
import com.householdledger.web.ui.view.TrialBalanceView;
import com.householdledger.web.ui.view.UnbalancedRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns module API types into the finished view models the templates render.
 *
 * <p><b>Why this exists as a class rather than as logic in the templates.</b>
 * Every derived figure on a page — a transaction's headline amount, a debit
 * column, a percentage share, the "showing 26–50 of 137" line — is arithmetic
 * about money or about counts. Thymeleaf can do all of it, and none of it
 * could then be unit-tested, reviewed by the compiler, or seen by anyone
 * reading the Java. Worse, the same calculation would be re-typed on each
 * page that needed it, which is how two pages end up disagreeing about the
 * amount of the same transaction. So the templates print strings and
 * booleans, and everything that is computed is computed here, once.
 *
 * <p><b>It computes nothing the ledger already knows.</b> Presentation signs
 * come from {@code reporting.api}, which got them from
 * {@code PresentationSign} (PRD §10). Balances come from the reporting
 * module's aggregates. This class only reshapes and formats: no account
 * balance, no total and no sign convention is defined here, so the UI cannot
 * drift away from the API's version of the same numbers.
 */
@Component
public class ViewAssembler {

    /** Section headings, in PRD §3.1's order. Plural, because a section holds a list. */
    public String heading(AccountType type) {
        return switch (type) {
            case ASSET -> "Assets";
            case LIABILITY -> "Liabilities";
            case INCOME -> "Income";
            case EXPENSE -> "Expenses";
            case EQUITY -> "Equity";
        };
    }

    /**
     * Accounts as dropdown choices, grouped by type in PRD §3.1's order.
     *
     * @param activeOnly true for entry forms, where a deactivated account
     *        would be a choice guaranteed to be refused (PRD §FR-2); false
     *        for filters, where retired accounts still have history to find
     */
    public List<AccountOptionGroup> optionGroups(List<Account> accounts, boolean activeOnly) {
        List<AccountOptionGroup> groups = new ArrayList<>();

        for (AccountType type : AccountType.values()) {
            List<AccountOption> options = accounts.stream()
                    .filter(account -> account.type() == type)
                    .filter(account -> !activeOnly || account.active())
                    .sorted(Comparator.comparing(Account::name, String.CASE_INSENSITIVE_ORDER))
                    .map(account -> new AccountOption(account.id(), account.name(), account.active()))
                    .toList();

            // An empty <optgroup> is a heading with nothing under it.
            if (!options.isEmpty()) {
                groups.add(new AccountOptionGroup(heading(type), options));
            }
        }

        return groups;
    }

    public List<AccountSection> sections(BalanceSheet balanceSheet) {
        return balanceSheet.sections().stream().map(this::section).toList();
    }

    private AccountSection section(BalanceSheetSection section) {
        List<AccountRow> rows = section.accounts().stream().map(this::accountRow).toList();

        return new AccountSection(
                section.type(),
                heading(section.type()),
                rows,
                MoneyFormat.format(section.sectionTotalMinor()),
                rows.isEmpty());
    }

    private AccountRow accountRow(AccountBalanceLine line) {
        return new AccountRow(
                line.accountId(),
                line.accountName(),
                line.type(),
                line.active(),
                MoneyFormat.format(line.balanceMinor()),
                line.signedBalanceMinor(),
                line.signFlipped(),
                line.balanceMinor() < 0L);
    }

    public TransactionRow transactionRow(TransactionDetail detail) {
        return new TransactionRow(
                detail.id(),
                detail.occurredOn(),
                detail.description(),
                MoneyFormat.format(headlineAmountMinor(detail)),
                summarise(detail),
                detail.reversed(),
                detail.isReversal());
    }

    public List<TransactionRow> transactionRows(List<TransactionDetail> details) {
        return details.stream().map(this::transactionRow).toList();
    }

    public TransactionDetailView detailView(TransactionDetail detail) {
        List<PostingRow> rows = detail.postings().stream()
                .map(posting -> new PostingRow(
                        posting.accountId(),
                        posting.accountName(),
                        posting.amountMinor() > 0L ? MoneyFormat.format(posting.amountMinor()) : "",
                        posting.amountMinor() < 0L ? MoneyFormat.formatMagnitude(posting.amountMinor()) : "",
                        posting.amountMinor() > 0L))
                .toList();

        long debits = sumSide(detail.postings(), true);
        long credits = sumSide(detail.postings(), false);

        return new TransactionDetailView(
                detail.id(),
                detail.occurredOn(),
                detail.description(),
                rows,
                MoneyFormat.format(debits),
                MoneyFormat.formatMagnitude(credits),
                Math.addExact(debits, credits) == 0L,
                detail.reversed(),
                detail.isReversal(),
                detail.reversesTransactionId(),
                // PRD §FR-4's two rules, in one place: reversible exactly
                // once, and a reversal is not itself reversible. The service
                // enforces both independently — this only decides whether the
                // button is worth showing.
                !detail.reversed() && !detail.isReversal());
    }

    public PageBar pageBar(PageResult<?> result) {
        boolean empty = result.isEmpty();
        long firstItem = empty ? 0L : (long) result.page() * result.size() + 1L;
        long lastItem = empty ? 0L : firstItem + result.content().size() - 1L;

        return new PageBar(
                result.page(),
                result.page() + 1,
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.hasPrevious(),
                result.hasNext(),
                Math.max(0, result.page() - 1),
                result.page() + 1,
                firstItem,
                lastItem,
                empty);
    }

    public List<ExpenseRow> expenseRows(ExpenseSummary summary) {
        // The denominator is the magnitude of the period's total. A period
        // that nets to zero (everything spent was refunded) would otherwise
        // divide by zero; its bars are simply all empty, which is honest.
        long denominator = magnitude(summary.totalMinor());

        return summary.lines().stream()
                .map(line -> new ExpenseRow(
                        line.accountId(),
                        line.accountName(),
                        MoneyFormat.format(line.totalMinor()),
                        line.totalMinor(),
                        share(line.totalMinor(), denominator),
                        barClass(share(line.totalMinor(), denominator)),
                        line.totalMinor() < 0L))
                .toList();
    }

    public TrialBalanceView trialBalanceView(TrialBalance trialBalance) {
        List<UnbalancedRow> offenders = trialBalance.unbalancedTransactions().stream()
                .map(row -> new UnbalancedRow(row.transactionId(), MoneyFormat.format(row.offByMinor())))
                .toList();

        return new TrialBalanceView(
                MoneyFormat.format(trialBalance.totalMinor()),
                trialBalance.totalMinor(),
                trialBalance.postingCount(),
                trialBalance.balanced(),
                trialBalance.isEmptyLedger(),
                offenders);
    }

    /**
     * The figure a person means by "how much was it": the sum of the positive
     * postings.
     *
     * <p>A transaction has no amount of its own (PRD §3.1) — the amount is
     * emergent from the postings, which is why the ledger stores no such
     * column. Taking the debit side gives the right answer for every entry
     * mode: for a simple entry it is the single destination, for a split it
     * is the whole bill, and for a reversal it is the same magnitude with the
     * sides swapped. Summing everything would give zero, which is true and
     * useless.
     */
    private long headlineAmountMinor(TransactionDetail detail) {
        return sumSide(detail.postings(), true);
    }

    /**
     * The signed sum of one side of a transaction: the debit side comes back
     * positive, the credit side negative.
     *
     * <p>Kept signed rather than converted to a magnitude here, so nothing
     * has to negate an individual posting. {@code Long.MIN_VALUE} has no
     * positive counterpart, and a display helper is the last place that
     * should be able to throw.
     */
    private long sumSide(List<PostingDetail> postings, boolean debitSide) {
        long total = 0L;
        for (PostingDetail posting : postings) {
            long amount = posting.amountMinor();
            if (amount != 0L && debitSide == (amount > 0L)) {
                total = Math.addExact(total, amount);
            }
        }
        return total;
    }

    /**
     * Magnitude without overflowing. {@code Math.abs(Long.MIN_VALUE)} is
     * negative, which would quietly invert a comparison; saturating to
     * {@code Long.MAX_VALUE} keeps a percentage in range instead.
     */
    private static long magnitude(long value) {
        if (value == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        return value < 0L ? -value : value;
    }

    /**
     * "Cash → Groceries", or "Cash → Groceries, Household, Transport" for a
     * split.
     *
     * <p>Sources are the credited accounts, destinations the debited ones,
     * which is what the arrow means to a reader who has never heard the words
     * debit and credit.
     */
    private String summarise(TransactionDetail detail) {
        List<String> sources = new ArrayList<>();
        List<String> destinations = new ArrayList<>();

        for (PostingDetail posting : detail.postings()) {
            if (posting.amountMinor() < 0L) {
                sources.add(posting.accountName());
            } else if (posting.amountMinor() > 0L) {
                destinations.add(posting.accountName());
            }
        }

        if (sources.isEmpty() || destinations.isEmpty()) {
            return String.join(", ", detail.postings().stream().map(PostingDetail::accountName).toList());
        }

        return String.join(", ", sources) + " → " + String.join(", ", destinations);
    }

    /**
     * The share as a CSS class, snapped to the nearest five percent.
     *
     * <p>Widths are classes because the UI's Content Security Policy forbids
     * inline styles; see the stylesheet. Snapping keeps the number of classes
     * to twenty-one rather than a hundred and one, which is finer than a 6px
     * bar can render anyway.
     */
    private String barClass(int percent) {
        return "bar--" + (Math.round(percent / 5.0f) * 5);
    }

    /**
     * Whole-number percentage share, rounded down and clamped to 0..100.
     *
     * <p>Rounded down and never re-totalled: these are bar widths, and the
     * report's own total comes from the reporting module. A page that added
     * up rounded percentages to reassure a reader they made 100 would be
     * inventing a number.
     */
    private int share(long amountMinor, long denominator) {
        if (denominator == 0L) {
            return 0;
        }
        long amount = magnitude(amountMinor);
        // Scaled down before multiplying when the figure is large enough that
        // x100 would overflow. A bar width does not need the last paisa, and
        // the result is clamped either way.
        long percent = amount <= Long.MAX_VALUE / 100L
                ? amount * 100L / denominator
                : amount / Math.max(1L, denominator / 100L);
        return (int) Math.clamp(percent, 0L, 100L);
    }
}
