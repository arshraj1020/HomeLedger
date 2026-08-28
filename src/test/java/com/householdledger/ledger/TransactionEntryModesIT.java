package com.householdledger.ledger;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import com.householdledger.ledger.api.*;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.ledger.domain.FutureDatedTransactionException;
import com.householdledger.ledger.domain.Transaction;
import com.householdledger.ledger.domain.UnbalancedTransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 4 transaction recording and reversal against real PostgreSQL 16
 * (PRD §FR-3, §FR-4).
 *
 * <p>Every mode is driven through the real service and then verified against
 * *derived balances* rather than by inspecting what was written — if the
 * postings were wrong, the balances would be wrong, and that is the property
 * the household actually cares about.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TransactionEntryModesIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("household_ledger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private LedgerService ledgerService;
    @Autowired private AccountService accountService;
    @Autowired private MemberProvisioningService provisioningService;

    private Household household;
    private UUID memberId;
    private UUID cash;
    private UUID card;
    private UUID groceries;
    private UUID electricity;

    @BeforeEach
    void provision() {
        household = provisioningService.createHousehold("Test Household");
        memberId = provisioningService.registerMember(household.id(), "Papa",
                "papa+" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple", Role.ADMIN).id();

        cash = accountId("Cash");
        groceries = accountId("Groceries");
        electricity = accountId("Electricity");
        card = accountService.createAccount(household.id(), AccountType.LIABILITY, "HDFC Card").id();
    }

    private UUID accountId(String name) {
        return accountService.listAccounts(household.id()).stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow().id();
    }

    private long balance(UUID accountId) {
        return ledgerService.accountBalanceMinor(household.id(), accountId, null);
    }

    // ---------- SIMPLE ----------

    @Test
    void simpleExpenseDebitsTheCategoryAndCreditsTheSource() {
        Transaction txn = ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Weekly groceries", memberId, card, groceries, 420_000);

        assertThat(txn.postings()).hasSize(2);
        assertThat(balance(groceries)).isEqualTo(420_000);
        assertThat(balance(card)).isEqualTo(-420_000);
    }

    @Test
    void simpleModeAlsoCoversIncomeTransfersAndCardPayments() {
        // PRD §FR-3: one mode, four real-world shapes — only the account
        // types differ, never the mechanics.
        UUID salary = accountService.createAccount(household.id(), AccountType.INCOME, "Papa's Salary").id();
        UUID savings = accountService.createAccount(household.id(), AccountType.ASSET, "HDFC Savings").id();

        ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(), "Salary", memberId,
                salary, savings, 5_000_000);
        ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(), "ATM withdrawal", memberId,
                savings, cash, 200_000);
        ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(), "Card payment", memberId,
                savings, card, 100_000);

        assertThat(balance(salary)).isEqualTo(-5_000_000);
        assertThat(balance(savings)).isEqualTo(5_000_000 - 200_000 - 100_000);
        assertThat(balance(cash)).isEqualTo(200_000);
        assertThat(balance(card)).isEqualTo(100_000);
    }

    @Test
    void simpleRejectsANonPositiveAmount() {
        assertThatThrownBy(() -> ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Zero", memberId, card, groceries, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Negative", memberId, card, groceries, -100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simpleRejectsTheSameAccountOnBothSides() {
        assertThatThrownBy(() -> ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Self transfer", memberId, cash, cash, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simpleRejectsAnAccountFromAnotherHousehold() {
        Household other = provisioningService.createHousehold("Other");
        UUID otherCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Cross household", memberId, otherCash, groceries, 100))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void simpleRejectsADeactivatedAccount() {
        accountService.setAccountActive(household.id(), card, false);

        assertThatThrownBy(() -> ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "On a closed card", memberId, card, groceries, 100))
                .isInstanceOf(InactiveAccountException.class);
    }

    // ---------- SPLIT ----------

    @Test
    void splitFundsSeveralCategoriesFromOneSource() {
        Transaction txn = ledgerService.recordSplitTransaction(household.id(), LocalDate.now(),
                "Combined utility bill", memberId, card,
                List.of(new SplitLine(groceries, 30_000), new SplitLine(electricity, 20_000)));

        assertThat(txn.postings()).hasSize(3);
        assertThat(balance(groceries)).isEqualTo(30_000);
        assertThat(balance(electricity)).isEqualTo(20_000);
        assertThat(balance(card)).isEqualTo(-50_000);
    }

    @Test
    void splitAcrossManyDestinationsStillBalancesExactly() {
        UUID rent = accountId("Rent");
        UUID transport = accountId("Transport");

        ledgerService.recordSplitTransaction(household.id(), LocalDate.now(), "Monthly", memberId, card,
                List.of(new SplitLine(groceries, 33_333), new SplitLine(electricity, 33_333),
                        new SplitLine(rent, 33_333), new SplitLine(transport, 1)));

        assertThat(balance(card)).isEqualTo(-100_000);
    }

    @Test
    void splitRejectsNoDestinations() {
        assertThatThrownBy(() -> ledgerService.recordSplitTransaction(household.id(), LocalDate.now(),
                "Empty", memberId, card, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitRejectsANonPositiveAllocation() {
        assertThatThrownBy(() -> ledgerService.recordSplitTransaction(household.id(), LocalDate.now(),
                "Bad", memberId, card, List.of(new SplitLine(groceries, -10))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitRejectsAnAccountFromAnotherHousehold() {
        Household other = provisioningService.createHousehold("Other");
        UUID otherGroceries = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Groceries")).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> ledgerService.recordSplitTransaction(household.id(), LocalDate.now(),
                "Mixed", memberId, card,
                List.of(new SplitLine(groceries, 100), new SplitLine(otherGroceries, 100))))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void nothingIsPersistedWhenASplitIsRejected() {
        assertThatThrownBy(() -> ledgerService.recordSplitTransaction(household.id(), LocalDate.now(),
                "Bad", memberId, card, List.of(new SplitLine(groceries, 0))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(balance(groceries)).isZero();
        assertThat(balance(card)).isZero();
    }

    // ---------- RAW ----------

    @Test
    void rawRecordsAnArbitrarySignedPostingList() {
        ledgerService.recordTransaction(household.id(), LocalDate.now(), "Raw entry", memberId,
                List.of(new PostingLine(groceries, 15_000), new PostingLine(electricity, 5_000),
                        new PostingLine(card, -20_000)));

        assertThat(balance(groceries)).isEqualTo(15_000);
        assertThat(balance(electricity)).isEqualTo(5_000);
        assertThat(balance(card)).isEqualTo(-20_000);
    }

    @Test
    void rawRejectsAnUnbalancedPostingSet() {
        assertThatThrownBy(() -> ledgerService.recordTransaction(household.id(), LocalDate.now(),
                "Unbalanced", memberId,
                List.of(new PostingLine(groceries, 100), new PostingLine(card, -99))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rawRejectsFewerThanTwoPostings() {
        assertThatThrownBy(() -> ledgerService.recordTransaction(household.id(), LocalDate.now(),
                "Single leg", memberId, List.of(new PostingLine(groceries, 100))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- date tolerance (FR-3) ----------

    @Test
    void aBackDatedTransactionIsAccepted() {
        assertThatCode(() -> ledgerService.recordSimpleTransaction(household.id(),
                LocalDate.now().minusMonths(6), "Old receipt", memberId, card, groceries, 100))
                .doesNotThrowAnyException();
    }

    @Test
    void aTransactionDatedFarInTheFutureIsRejected() {
        assertThatThrownBy(() -> ledgerService.recordSimpleTransaction(household.id(),
                LocalDate.now().plusDays(30), "Next month", memberId, card, groceries, 100))
                .isInstanceOf(FutureDatedTransactionException.class);
    }

    @Test
    void theFutureDateRuleAppliesToEveryMode() {
        LocalDate farFuture = LocalDate.now().plusYears(1);

        assertThatThrownBy(() -> ledgerService.recordSplitTransaction(household.id(), farFuture,
                "Split", memberId, card, List.of(new SplitLine(groceries, 100))))
                .isInstanceOf(FutureDatedTransactionException.class);

        assertThatThrownBy(() -> ledgerService.recordTransaction(household.id(), farFuture, "Raw", memberId,
                List.of(new PostingLine(groceries, 100), new PostingLine(card, -100))))
                .isInstanceOf(FutureDatedTransactionException.class);
    }

    // ---------- retrieval ----------

    @Test
    void getTransactionReturnsFullPostingDetailWithAccountNames() {
        Transaction txn = ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Weekly groceries", memberId, card, groceries, 420_000);

        TransactionDetail detail = ledgerService.getTransaction(household.id(), txn.id());

        assertThat(detail.id()).isEqualTo(txn.id());
        assertThat(detail.description()).isEqualTo("Weekly groceries");
        assertThat(detail.reversed()).isFalse();
        assertThat(detail.isReversal()).isFalse();
        assertThat(detail.postings()).hasSize(2);
        assertThat(detail.postings()).extracting(PostingDetail::accountName)
                .containsExactlyInAnyOrder("Groceries", "HDFC Card");
        assertThat(detail.postings()).extracting(PostingDetail::amountMinor)
                .containsExactlyInAnyOrder(420_000L, -420_000L);
    }

    @Test
    void getTransactionFromAnotherHouseholdIsNotFound() {
        Household other = provisioningService.createHousehold("Other");
        UUID otherMember = provisioningService.registerMember(other.id(), "B",
                "b+" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple", Role.ADMIN).id();
        UUID otherCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();
        UUID otherGroceries = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Groceries")).findFirst().orElseThrow().id();

        Transaction theirs = ledgerService.recordSimpleTransaction(other.id(), LocalDate.now(),
                "Theirs", otherMember, otherCash, otherGroceries, 100);

        assertThatThrownBy(() -> ledgerService.getTransaction(household.id(), theirs.id()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void getTransactionWithAnUnknownIdIsNotFound() {
        assertThatThrownBy(() -> ledgerService.getTransaction(household.id(), UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    // ---------- reversal (FR-4) ----------

    @Test
    void reversalInvertsEveryPostingAndRestoresBalances() {
        Transaction original = ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Weekly groceries", memberId, card, groceries, 420_000);

        ledgerService.reverseTransaction(household.id(), original.id(), memberId);

        assertThat(balance(groceries)).isZero();
        assertThat(balance(card)).isZero();
    }

    @Test
    void bothTransactionsRemainQueryableAfterReversal() {
        // PRD §3.5: neither is deleted or edited — the ledger stays a complete
        // audit log.
        Transaction original = ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Weekly groceries", memberId, card, groceries, 420_000);
        Transaction reversal = ledgerService.reverseTransaction(household.id(), original.id(), memberId);

        TransactionDetail originalDetail = ledgerService.getTransaction(household.id(), original.id());
        TransactionDetail reversalDetail = ledgerService.getTransaction(household.id(), reversal.id());

        assertThat(originalDetail.reversed()).isTrue();
        assertThat(originalDetail.isReversal()).isFalse();
        assertThat(reversalDetail.isReversal()).isTrue();
        assertThat(reversalDetail.reversesTransactionId()).isEqualTo(original.id());
        assertThat(reversalDetail.description()).contains("Reversal of");
    }

    @Test
    void reversingTwiceIsRejected() {
        Transaction original = ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Weekly groceries", memberId, card, groceries, 100);
        ledgerService.reverseTransaction(household.id(), original.id(), memberId);

        assertThatThrownBy(() -> ledgerService.reverseTransaction(household.id(), original.id(), memberId))
                .isInstanceOf(TransactionAlreadyReversedException.class);
    }

    @Test
    void aReversalCannotItselfBeReversed() {
        Transaction original = ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Weekly groceries", memberId, card, groceries, 100);
        Transaction reversal = ledgerService.reverseTransaction(household.id(), original.id(), memberId);

        assertThatThrownBy(() -> ledgerService.reverseTransaction(household.id(), reversal.id(), memberId))
                .isInstanceOf(ReversalTransactionCannotBeReversedException.class);
    }

    @Test
    void aSplitTransactionReversesEveryLeg() {
        Transaction original = ledgerService.recordSplitTransaction(household.id(), LocalDate.now(),
                "Combined bill", memberId, card,
                List.of(new SplitLine(groceries, 30_000), new SplitLine(electricity, 20_000)));

        ledgerService.reverseTransaction(household.id(), original.id(), memberId);

        assertThat(balance(groceries)).isZero();
        assertThat(balance(electricity)).isZero();
        assertThat(balance(card)).isZero();
    }

    @Test
    void aTransactionOnADeactivatedAccountCanStillBeReversed() {
        // Deliberate: PRD §3.5 makes reversal the only correction path, so
        // deactivating an account must not strand an error permanently.
        Transaction original = ledgerService.recordSimpleTransaction(household.id(), LocalDate.now(),
                "Mistake", memberId, card, groceries, 100);
        accountService.setAccountActive(household.id(), card, false);

        assertThatCode(() -> ledgerService.reverseTransaction(household.id(), original.id(), memberId))
                .doesNotThrowAnyException();
        assertThat(balance(card)).isZero();
    }

    @Test
    void reversingAnotherHouseholdsTransactionIsNotFound() {
        Household other = provisioningService.createHousehold("Other");
        UUID otherMember = provisioningService.registerMember(other.id(), "B",
                "b+" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple", Role.ADMIN).id();
        UUID otherCash = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Cash")).findFirst().orElseThrow().id();
        UUID otherGroceries = accountService.listAccounts(other.id()).stream()
                .filter(a -> a.name().equals("Groceries")).findFirst().orElseThrow().id();
        Transaction theirs = ledgerService.recordSimpleTransaction(other.id(), LocalDate.now(),
                "Theirs", otherMember, otherCash, otherGroceries, 100);

        assertThatThrownBy(() -> ledgerService.reverseTransaction(household.id(), theirs.id(), memberId))
                .isInstanceOf(TransactionNotFoundException.class);
        // And theirs is untouched.
        assertThat(ledgerService.accountBalanceMinor(other.id(), otherGroceries, null)).isEqualTo(100);
    }
}
