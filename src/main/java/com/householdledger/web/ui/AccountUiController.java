package com.householdledger.web.ui;

import com.householdledger.identity.api.AuthenticatedMember;
import com.householdledger.ledger.api.AccountService;
import com.householdledger.ledger.api.LedgerService;
import com.householdledger.ledger.domain.Account;
import com.householdledger.ledger.domain.AccountType;
import com.householdledger.reporting.api.BalanceSheet;
import com.householdledger.reporting.api.ReportingService;
import com.householdledger.web.ui.form.AccountEditForm;
import com.householdledger.web.ui.form.AccountForm;
import com.householdledger.web.ui.support.ViewAssembler;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * The chart of accounts (PRD §FR-2, §FR-7).
 *
 * <p><b>Authorisation is the same annotation the API uses.</b> PRD §FR-1
 * reserves account management to admins, and {@code @PreAuthorize} enforces
 * it here exactly as it does on {@code AccountController}. The templates also
 * hide the buttons a member may not use, but that is politeness: hiding a
 * control is not a permission check, and a member who types the URL still
 * gets a 403 page from the same annotation.
 *
 * <p><b>Balances come from the balance sheet, not from a per-account loop.</b>
 * The reporting module already produces every account with its balance in one
 * query (PRD §FR-5), so listing them costs one round trip rather than one per
 * account — the difference between a page that stays fast at fifty accounts
 * and one that does not (PRD §5).
 *
 * <p>The household is taken from the authenticated principal on every method,
 * never from the path (PRD §FR-1). An account id from another household does
 * not resolve, and the service raises the same not-found it would for an id
 * that never existed — which the UI renders as 404, not 403 (PRD §9).
 */
@Controller
@RequestMapping("/accounts")
class AccountUiController {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final ReportingService reportingService;
    private final ViewAssembler assembler;

    AccountUiController(AccountService accountService, LedgerService ledgerService,
                        ReportingService reportingService, ViewAssembler assembler) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.reportingService = reportingService;
        this.assembler = assembler;
    }

    @GetMapping
    String list(@AuthenticationPrincipal AuthenticatedMember member, Model model) {
        BalanceSheet balanceSheet = reportingService.balanceSheet(member.householdId(), null);

        long total = balanceSheet.sections().stream()
                .mapToLong(section -> section.accounts().size())
                .sum();
        long inactive = balanceSheet.sections().stream()
                .flatMap(section -> section.accounts().stream())
                .filter(account -> !account.active())
                .count();

        model.addAttribute(UiModel.NAV, UiModel.NAV_ACCOUNTS);
        model.addAttribute("sections", assembler.sections(balanceSheet));
        model.addAttribute("accountCount", total);
        model.addAttribute("inactiveCount", inactive);

        return "accounts/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    String newAccountForm(Model model) {
        model.addAttribute(UiModel.NAV, UiModel.NAV_ACCOUNTS);
        model.addAttribute("accountTypes", AccountType.values());
        if (!model.containsAttribute("accountForm")) {
            model.addAttribute("accountForm", new AccountForm());
        }
        return "accounts/new";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    String create(@AuthenticationPrincipal AuthenticatedMember member,
                  @Valid @ModelAttribute("accountForm") AccountForm form,
                  BindingResult binding,
                  Model model,
                  RedirectAttributes redirect) {

        if (binding.hasErrors()) {
            model.addAttribute(UiModel.NAV, UiModel.NAV_ACCOUNTS);
            model.addAttribute("accountTypes", AccountType.values());
            return "accounts/new";
        }

        Account created = accountService.createAccount(
                member.householdId(), form.getType(), form.getName().trim());

        redirect.addFlashAttribute("flash", "Created the account " + created.name() + ".");
        return "redirect:/accounts";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    String editForm(@AuthenticationPrincipal AuthenticatedMember member,
                    @PathVariable UUID id,
                    Model model) {

        Account account = ledgerService.getAccount(member.householdId(), id);

        if (!model.containsAttribute("accountEditForm")) {
            AccountEditForm form = new AccountEditForm();
            form.setName(account.name());
            form.setActive(account.active());
            model.addAttribute("accountEditForm", form);
        }

        addEditContext(model, account);
        return "accounts/edit";
    }

    /**
     * Applies a rename and an activation change, in that order, and only when
     * something actually changed.
     *
     * <p>Calling {@code renameAccount} with the name it already has would
     * raise a duplicate-name conflict against the account itself, so the
     * comparison is not an optimisation — it is what makes "save" work when a
     * member only toggled the checkbox.
     */
    @PostMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String update(@AuthenticationPrincipal AuthenticatedMember member,
                  @PathVariable UUID id,
                  @Valid @ModelAttribute("accountEditForm") AccountEditForm form,
                  BindingResult binding,
                  Model model,
                  RedirectAttributes redirect) {

        UUID householdId = member.householdId();
        Account account = ledgerService.getAccount(householdId, id);

        if (binding.hasErrors()) {
            addEditContext(model, account);
            return "accounts/edit";
        }

        String newName = form.getName().trim();
        Account updated = account;

        if (!newName.equals(account.name())) {
            updated = accountService.renameAccount(householdId, id, newName);
        }
        if (form.isActive() != account.active()) {
            updated = accountService.setAccountActive(householdId, id, form.isActive());
        }

        redirect.addFlashAttribute("flash", "Saved changes to " + updated.name() + ".");
        return "redirect:/accounts";
    }

    private void addEditContext(Model model, Account account) {
        model.addAttribute(UiModel.NAV, UiModel.NAV_ACCOUNTS);
        model.addAttribute("accountId", account.id());
        model.addAttribute("accountName", account.name());
        model.addAttribute("accountTypeHeading", assembler.heading(account.type()));
    }
}
