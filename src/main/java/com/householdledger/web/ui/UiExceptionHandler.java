package com.householdledger.web.ui;

import com.householdledger.identity.api.InvalidCredentialsException;
import com.householdledger.ledger.api.AccountNameAlreadyExistsException;
import com.householdledger.ledger.api.AccountNotFoundException;
import com.householdledger.ledger.api.InactiveAccountException;
import com.householdledger.ledger.api.ReversalTransactionCannotBeReversedException;
import com.householdledger.ledger.api.TransactionAlreadyReversedException;
import com.householdledger.ledger.api.TransactionNotFoundException;
import com.householdledger.ledger.domain.FutureDatedTransactionException;
import com.householdledger.ledger.domain.UnbalancedTransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * Turns the same exceptions the API answers with RFC 7807 documents into HTML
 * pages for the browser (PRD §9).
 *
 * <p><b>Why this is scoped and ordered rather than replacing anything.</b>
 * The two existing {@code @RestControllerAdvice} classes define the API's
 * error contract, and Phase 7 must not change it — an API client's 404 body
 * has to stay a problem document. So this advice is restricted to
 * {@code basePackages} of the UI and given the highest precedence, and the
 * REST advices are restricted to {@code @RestController} classes. Either
 * restriction alone would be enough; both together mean the outcome does not
 * depend on the order Spring happens to discover advice beans in.
 *
 * <p><b>Nothing from the exception reaches the page.</b> Every message here
 * is a fixed string. The exception messages themselves name identifiers —
 * {@code "No account 3f2b… in this household"} — and PRD §9 rules that out:
 * an id that resolves for one member and not another is exactly the existence
 * signal that returning 404 instead of 403 exists to suppress. The detail is
 * logged server-side, where an operator can see it and a browser cannot.
 *
 * <p>Statuses match the API's for the same conditions, so the two front doors
 * never disagree about what went wrong — only about how to say it.
 */
@ControllerAdvice(basePackages = "com.householdledger.web.ui")
@Order(Ordered.HIGHEST_PRECEDENCE)
class UiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UiExceptionHandler.class);

    private static final String VIEW = "error/problem";

    /**
     * 404 for both "no such thing" and "belongs to another household" — the
     * services already refuse to distinguish them (PRD §FR-1, §9), and the
     * page must not undo that by wording the two differently.
     */
    @ExceptionHandler({AccountNotFoundException.class, TransactionNotFoundException.class})
    ModelAndView handleNotFound(RuntimeException e) {
        log.debug("UI request for a resource outside the caller's household or not present", e);
        return page(HttpStatus.NOT_FOUND,
                "Not found",
                "We couldn't find that in your household. It may have been removed, or the link may be wrong.");
    }

    @ExceptionHandler(AccountNameAlreadyExistsException.class)
    ModelAndView handleDuplicateName(AccountNameAlreadyExistsException e) {
        return page(HttpStatus.CONFLICT,
                "That name is taken",
                "Another account in your household already uses that name. Pick a different one.");
    }

    @ExceptionHandler(TransactionAlreadyReversedException.class)
    ModelAndView handleAlreadyReversed(TransactionAlreadyReversedException e) {
        return page(HttpStatus.CONFLICT,
                "Already reversed",
                "This transaction has already been reversed. A transaction can be reversed once.");
    }

    @ExceptionHandler(ReversalTransactionCannotBeReversedException.class)
    ModelAndView handleReversalOfReversal(ReversalTransactionCannotBeReversedException e) {
        return page(HttpStatus.CONFLICT,
                "Reversals can't be reversed",
                "This entry is itself a reversal. To undo it, reverse the original transaction instead.");
    }

    @ExceptionHandler(InactiveAccountException.class)
    ModelAndView handleInactiveAccount(InactiveAccountException e) {
        return page(HttpStatus.UNPROCESSABLE_ENTITY,
                "That account is deactivated",
                "Deactivated accounts keep their history but can't take new entries. Reactivate it first, "
                        + "or choose another account.");
    }

    @ExceptionHandler(UnbalancedTransactionException.class)
    ModelAndView handleUnbalanced(UnbalancedTransactionException e) {
        return page(HttpStatus.UNPROCESSABLE_ENTITY,
                "That entry doesn't balance",
                "Every transaction must move the same total out as it moves in, and this one doesn't. "
                        + "Nothing was recorded.");
    }

    @ExceptionHandler(FutureDatedTransactionException.class)
    ModelAndView handleFutureDated(FutureDatedTransactionException e) {
        return page(HttpStatus.UNPROCESSABLE_ENTITY,
                "That date is too far ahead",
                "Transactions record what has happened, so the date can't be in the future.");
    }

    /**
     * Method-level authorisation, refused (PRD §FR-1: only an ADMIN manages
     * accounts).
     *
     * <p>403 here rather than 404 because this is not about existence: the
     * member is looking at their own household's page and simply may not
     * perform the action. Nothing is revealed by saying so.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ModelAndView handleAccessDenied(AccessDeniedException e) {
        return page(HttpStatus.FORBIDDEN,
                "Not allowed",
                "Managing accounts is limited to household admins. Ask an admin in your household to do this.");
    }

    /**
     * A member somehow reaching a page with an unusable session goes back to
     * the login form rather than being shown an error about credentials.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    ModelAndView handleInvalidCredentials(InvalidCredentialsException e) {
        return new ModelAndView("redirect:/login?error");
    }

    /**
     * The catch-all for validation that got past the form and into a service.
     *
     * <p>Message is fixed rather than taken from the exception: most of these
     * are caught by the forms and never reach here, so the ones that do are
     * more likely to be a bug than something a member can act on, and their
     * text has not been reviewed for what it might name.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ModelAndView handleIllegalArgument(IllegalArgumentException e) {
        log.warn("UI request rejected as invalid", e);
        return page(HttpStatus.BAD_REQUEST,
                "We couldn't process that",
                "Something in that request wasn't valid. Go back and check the values, then try again.");
    }

    private ModelAndView page(HttpStatus status, String title, String message) {
        ModelAndView view = new ModelAndView(VIEW, status);
        view.addObject("problemStatus", status.value());
        view.addObject("problemTitle", title);
        view.addObject("problemMessage", message);
        // The @ModelAttribute advice does not run for exception handling, so
        // the layout's chrome is added here or the page renders signed-out.
        UiModel.addChrome(view.getModelMap());
        return view;
    }
}
