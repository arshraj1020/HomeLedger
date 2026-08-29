package com.householdledger.web.ui;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.ledger.api.AccountNotFoundException;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.InactiveAccountException;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.api.PageResult;
import com.householdledger.ledger.api.PostingLine;
import com.householdledger.ledger.api.SplitLine;
import com.householdledger.ledger.api.TransactionDetail;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.FutureDatedTransactionException;
import com.householdledger.ledger.domain.PageSpec;
import com.householdledger.ledger.domain.Transaction;
import com.householdledger.ledger.domain.TransactionFilter;
import com.householdledger.ledger.domain.UnbalancedTransactionException;
import com.householdledger.web.ui.form.EntryLine;
import com.householdledger.web.ui.form.RawEntryForm;
import com.householdledger.web.ui.form.SimpleEntryForm;
import com.householdledger.web.ui.form.SplitEntryForm;
import com.householdledger.web.ui.form.TransactionFilterForm;
import com.householdledger.web.ui.support.MoneyInput;
import com.householdledger.web.ui.support.ViewAssembler;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recording, finding, reading and reversing transactions — the heart of the
 * UI (PRD §FR-3, §FR-4, §FR-5, §FR-7).
 *
 * <p><b>Three entry modes, three endpoints.</b> The API takes a single
 * request with a {@code mode} discriminator, which suits a client that
 * already knows which shape it is sending. A browser form does not: one
 * endpoint would mean one form object carrying every mode's fields, with
 * validation that has to ask which fields matter this time, and a redisplay
 * that has to remember which tab the member was on. Separate forms and
 * separate endpoints keep each mode's rules where they can be read.
 *
 * <p><b>Amounts stay text until they are parsed here.</b> Binding straight to
 * a number would turn a typo into a framework conversion error with no field
 * context. {@code MoneyInput} produces {@code long} minor units or a message
 * fit to show a member (PRD §3.3).
 *
 * <p><b>Service failures become field errors where they can.</b> A future
 * date, an unbalanced raw entry or a deactivated account are things a member
 * can fix in the form they are looking at, so they are reported there with
 * their work intact rather than as a full-page error. The services still
 * decide — nothing here re-implements a rule — and anything not mapped falls
 * through to {@link UiExceptionHandler}.
 *
 * <p><b>The household never comes from the request.</b> Every call passes
 * {@code member.householdId()} from the verified principal (PRD §FR-1). A
 * transaction id from another household raises the same not-found as one that
 * does not exist, which renders as 404 (PRD §9).
 */
@Controller
@RequestMapping("/transactions")
class TransactionUiController {

    private final LedgerService ledgerService;
    private final AccountService accountService;
    private final ViewAssembler assembler;

    TransactionUiController(LedgerService ledgerService, AccountService accountService,
                            ViewAssembler assembler) {
        this.ledgerService = ledgerService;
        this.accountService = accountService;
        this.assembler = assembler;
    }

    // ---------------------------------------------------------------- list

    @GetMapping
    String list(@AuthenticationPrincipal AuthenticatedMember member,
                @ModelAttribute("filter") TransactionFilterForm filter,
                @RequestParam(required = false) Integer page,
                @RequestParam(required = false) Integer size,
                Model model) {

        UUID householdId = member.householdId();

        model.addAttribute(UiModel.NAV, UiModel.NAV_TRANSACTIONS);
        model.addAttribute("accountGroups",
                assembler.optionGroups(accountService.listAccounts(householdId), false));
        model.addAttribute("filterActive", filter.isActive());

        // Reversed ranges are reported rather than silently swapped: swapping
        // would answer a question the member did not ask, with a
        // plausible-looking list. The domain filter would reject it anyway;
        // catching it here keeps their input on screen.
        if (filter.isReversedRange()) {
            model.addAttribute("filterError",
                    "The 'from' date is after the 'to' date, so nothing could match. Swap them to search.");
            model.addAttribute("transactions", List.of());
            model.addAttribute("pageBar", assembler.pageBar(PageResult.empty(0, PageSpec.DEFAULT_SIZE)));
            return "transactions/list";
        }

        PageResult<TransactionDetail> result = ledgerService.findTransactions(
                householdId,
                new TransactionFilter(filter.getFrom(), filter.getTo(), filter.getAccountId(), null, filter.getQ()),
                PageSpec.of(page, size));

        model.addAttribute("transactions", assembler.transactionRows(result.content()));
        model.addAttribute("pageBar", assembler.pageBar(result));

        return "transactions/list";
    }

    // -------------------------------------------------------------- detail

    @GetMapping("/{id}")
    String detail(@AuthenticationPrincipal AuthenticatedMember member,
                  @PathVariable UUID id,
                  Model model) {

        TransactionDetail detail = ledgerService.getTransaction(member.householdId(), id);

        model.addAttribute(UiModel.NAV, UiModel.NAV_TRANSACTIONS);
        model.addAttribute("transaction", assembler.detailView(detail));

        return "transactions/detail";
    }

    /**
     * Reversal (PRD §FR-4): a new, opposite transaction, never an edit or a
     * delete of the original. The member is taken to the reversal so they can
     * see what was actually written.
     */
    @PostMapping("/{id}/reverse")
    String reverse(@AuthenticationPrincipal AuthenticatedMember member,
                   @PathVariable UUID id,
                   RedirectAttributes redirect) {

        Transaction reversal = ledgerService.reverseTransaction(member.householdId(), id, member.memberId());

        redirect.addFlashAttribute("flash",
                "Reversed. The original is unchanged and this entry cancels it.");

        return "redirect:/transactions/" + reversal.id();
    }

    // --------------------------------------------------------------- entry

    /**
     * The entry page. The mode is a query parameter and the tabs are ordinary
     * links, so switching modes is a normal page load rather than something
     * that needs scripting.
     */
    @GetMapping("/new")
    String newTransactionForm(@AuthenticationPrincipal AuthenticatedMember member,
                              @RequestParam(required = false, defaultValue = "simple") String mode,
                              Model model) {

        String resolved = resolveMode(mode);

        if (!model.containsAttribute("simpleForm")) {
            model.addAttribute("simpleForm", new SimpleEntryForm());
        }
        if (!model.containsAttribute("splitForm")) {
            SplitEntryForm split = new SplitEntryForm();
            split.padTo(SplitEntryForm.DEFAULT_ROWS);
            model.addAttribute("splitForm", split);
        }
        if (!model.containsAttribute("rawForm")) {
            RawEntryForm raw = new RawEntryForm();
            raw.padTo(RawEntryForm.DEFAULT_ROWS);
            model.addAttribute("rawForm", raw);
        }

        addEntryContext(member, model, resolved);
        return "transactions/new";
    }

    @PostMapping("/new/simple")
    String recordSimple(@AuthenticationPrincipal AuthenticatedMember member,
                        @Valid @ModelAttribute("simpleForm") SimpleEntryForm form,
                        BindingResult binding,
                        Model model,
                        RedirectAttributes redirect) {

        long amountMinor = 0L;
        try {
            amountMinor = MoneyInput.parsePositive(form.getAmount());
        } catch (IllegalArgumentException e) {
            binding.rejectValue("amount", "amount.invalid", e.getMessage());
        }

        if (form.getFromAccountId() != null && form.getFromAccountId().equals(form.getToAccountId())) {
            binding.rejectValue("toAccountId", "toAccountId.same",
                    "Money has to move between two different accounts.");
        }

        if (!binding.hasErrors()) {
            try {
                Transaction recorded = ledgerService.recordSimpleTransaction(
                        member.householdId(), form.getOccurredOn(), form.getDescription().trim(),
                        member.memberId(), form.getFromAccountId(), form.getToAccountId(), amountMinor);

                redirect.addFlashAttribute("flash", "Recorded.");
                return "redirect:/transactions/" + recorded.id();
            } catch (FutureDatedTransactionException e) {
                rejectDate(binding);
            } catch (InactiveAccountException e) {
                binding.reject("entry.inactiveAccount",
                        "One of those accounts is deactivated and can't take new entries.");
            } catch (AccountNotFoundException e) {
                binding.reject("entry.unknownAccount", "Choose accounts from your household's list.");
            } catch (UnbalancedTransactionException e) {
                binding.reject("entry.unbalanced", "That entry doesn't balance, so nothing was recorded.");
            }
        }

        return redisplay(member, model, "simple", "simpleForm", form);
    }

    @PostMapping("/new/split")
    String recordSplit(@AuthenticationPrincipal AuthenticatedMember member,
                       @Valid @ModelAttribute("splitForm") SplitEntryForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes redirect) {

        List<SplitLine> destinations = new ArrayList<>();
        List<EntryLine> rows = form.getDestinations();
        boolean anyRowRejected = false;

        for (int i = 0; i < rows.size(); i++) {
            EntryLine row = rows.get(i);
            if (row.isBlank()) {
                continue;
            }
            if (row.getAccountId() == null) {
                binding.rejectValue("destinations[" + i + "].accountId", "line.account",
                        "Choose an account for this line, or clear the amount.");
                anyRowRejected = true;
                continue;
            }
            if (row.getAccountId().equals(form.getFromAccountId())) {
                binding.rejectValue("destinations[" + i + "].accountId", "line.sameAsSource",
                        "This is the account the money came from. Choose a different one.");
                anyRowRejected = true;
                continue;
            }
            try {
                destinations.add(new SplitLine(row.getAccountId(), MoneyInput.parsePositive(row.getAmount())));
            } catch (IllegalArgumentException e) {
                binding.rejectValue("destinations[" + i + "].amount", "line.amount", e.getMessage());
                anyRowRejected = true;
            }
        }

        // Only complain that the form is empty when no row was rejected;
        // otherwise the member would get "add a line" alongside the errors on
        // the lines they did add.
        if (destinations.isEmpty() && !anyRowRejected) {
            binding.reject("entry.noDestinations", "Add at least one line saying where the money went.");
        }

        if (!binding.hasErrors()) {
            try {
                Transaction recorded = ledgerService.recordSplitTransaction(
                        member.householdId(), form.getOccurredOn(), form.getDescription().trim(),
                        member.memberId(), form.getFromAccountId(), destinations);

                redirect.addFlashAttribute("flash", "Recorded.");
                return "redirect:/transactions/" + recorded.id();
            } catch (FutureDatedTransactionException e) {
                rejectDate(binding);
            } catch (InactiveAccountException e) {
                binding.reject("entry.inactiveAccount",
                        "One of those accounts is deactivated and can't take new entries.");
            } catch (AccountNotFoundException e) {
                binding.reject("entry.unknownAccount", "Choose accounts from your household's list.");
            } catch (UnbalancedTransactionException e) {
                binding.reject("entry.unbalanced", "That entry doesn't balance, so nothing was recorded.");
            }
        }

        form.padTo(SplitEntryForm.DEFAULT_ROWS);
        return redisplay(member, model, "split", "splitForm", form);
    }

    @PostMapping("/new/raw")
    String recordRaw(@AuthenticationPrincipal AuthenticatedMember member,
                     @Valid @ModelAttribute("rawForm") RawEntryForm form,
                     BindingResult binding,
                     Model model,
                     RedirectAttributes redirect) {

        List<PostingLine> postings = new ArrayList<>();
        List<EntryLine> rows = form.getPostings();
        long total = 0L;
        boolean amountsUsable = true;

        for (int i = 0; i < rows.size(); i++) {
            EntryLine row = rows.get(i);
            if (row.isBlank()) {
                continue;
            }
            if (row.getAccountId() == null) {
                binding.rejectValue("postings[" + i + "].accountId", "line.account",
                        "Choose an account for this line, or clear the amount.");
                amountsUsable = false;
                continue;
            }
            try {
                long amount = MoneyInput.parse(row.getAmount());
                if (amount == 0L) {
                    binding.rejectValue("postings[" + i + "].amount", "line.zero",
                            "A posting of zero moves nothing. Remove the line or enter an amount.");
                    amountsUsable = false;
                    continue;
                }
                postings.add(new PostingLine(row.getAccountId(), amount));
                total = Math.addExact(total, amount);
            } catch (IllegalArgumentException e) {
                binding.rejectValue("postings[" + i + "].amount", "line.amount", e.getMessage());
                amountsUsable = false;
            }
        }

        // Both of these are suppressed when a row was already rejected: a
        // member who mistyped one amount should see that, not a second
        // complaint that the entry is short a line or does not add up.
        if (amountsUsable && postings.size() < 2) {
            binding.reject("entry.tooFewPostings", "A transaction needs at least two lines.");
        } else if (amountsUsable && postings.size() >= 2 && total != 0L) {
            // Reported here so the member keeps their work; the domain, the
            // service and the database trigger all still refuse it
            // independently (PRD §3.2).
            binding.reject("entry.unbalanced",
                    "These lines don't cancel out, so the entry doesn't balance. "
                            + "Debits are positive and credits are negative, and the two sides must be equal.");
        }

        if (!binding.hasErrors()) {
            try {
                Transaction recorded = ledgerService.recordTransaction(
                        member.householdId(), form.getOccurredOn(), form.getDescription().trim(),
                        member.memberId(), postings);

                redirect.addFlashAttribute("flash", "Recorded.");
                return "redirect:/transactions/" + recorded.id();
            } catch (FutureDatedTransactionException e) {
                rejectDate(binding);
            } catch (InactiveAccountException e) {
                binding.reject("entry.inactiveAccount",
                        "One of those accounts is deactivated and can't take new entries.");
            } catch (AccountNotFoundException e) {
                binding.reject("entry.unknownAccount", "Choose accounts from your household's list.");
            } catch (UnbalancedTransactionException e) {
                binding.reject("entry.unbalanced", "That entry doesn't balance, so nothing was recorded.");
            }
        }

        form.padTo(RawEntryForm.DEFAULT_ROWS);
        return redisplay(member, model, "raw", "rawForm", form);
    }

    // ------------------------------------------------------------- helpers

    private static void rejectDate(BindingResult binding) {
        binding.rejectValue("occurredOn", "occurredOn.future",
                "Transactions record what has happened, so the date can't be in the future.");
    }

    /**
     * Re-renders the entry page on the failed mode's tab, keeping the
     * submitted form and adding back the other two modes' blank forms — the
     * page shows all three tabs, so all three model attributes have to exist
     * whichever one was posted.
     */
    private String redisplay(AuthenticatedMember member, Model model,
                             String mode, String attribute, Object form) {

        model.addAttribute(attribute, form);

        if (!"simple".equals(mode)) {
            model.addAttribute("simpleForm", new SimpleEntryForm());
        }
        if (!"split".equals(mode)) {
            SplitEntryForm split = new SplitEntryForm();
            split.padTo(SplitEntryForm.DEFAULT_ROWS);
            model.addAttribute("splitForm", split);
        }
        if (!"raw".equals(mode)) {
            RawEntryForm raw = new RawEntryForm();
            raw.padTo(RawEntryForm.DEFAULT_ROWS);
            model.addAttribute("rawForm", raw);
        }

        addEntryContext(member, model, mode);
        return "transactions/new";
    }

    private void addEntryContext(AuthenticatedMember member, Model model, String mode) {
        List<Account> accounts = accountService.listAccounts(member.householdId());

        model.addAttribute(UiModel.NAV, UiModel.NAV_TRANSACTIONS);
        model.addAttribute("mode", mode);
        // Entry forms offer active accounts only: a deactivated account is
        // guaranteed to be refused a posting (PRD §FR-2), so listing it would
        // be offering a choice that cannot work.
        model.addAttribute("accountGroups", assembler.optionGroups(accounts, true));
        model.addAttribute("hasAccounts", accounts.stream().anyMatch(Account::active));
        model.addAttribute("splitRows", SplitEntryForm.DEFAULT_ROWS);
        model.addAttribute("rawRows", RawEntryForm.DEFAULT_ROWS);
    }

    /** Unknown modes fall back to simple rather than erroring: a bad tab in a URL is not worth a 400. */
    private static String resolveMode(String mode) {
        return switch (mode == null ? "" : mode.toLowerCase(java.util.Locale.ROOT)) {
            case "split" -> "split";
            case "raw" -> "raw";
            default -> "simple";
        };
    }
}
