package com.householdledger.web.ui;

import com.householdledger.identity.api.AuthenticatedMember;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;

import java.util.function.BiConsumer;

/**
 * The handful of attributes the shared layout needs on every page: who is
 * signed in, whether they are an admin, and which navigation item to mark
 * current.
 *
 * <p>Reading the principal from {@link SecurityContextHolder} rather than
 * taking it as a method parameter is deliberate and confined to this class.
 * Page chrome has to render on error responses too, and an
 * {@code @ExceptionHandler} does not re-run the {@code @ModelAttribute}
 * methods that populate a normal request's model — so a 404 page would come
 * out signed-out-looking while the member is signed in. One code path that
 * works in both places is better than two that differ.
 *
 * <p>Controllers still take {@code @AuthenticationPrincipal AuthenticatedMember}
 * as a parameter for everything that matters. Household scoping never comes
 * from here: this is chrome.
 */
final class UiModel {

    static final String NAV = "activeNav";
    static final String NAV_DASHBOARD = "dashboard";
    static final String NAV_TRANSACTIONS = "transactions";
    static final String NAV_ACCOUNTS = "accounts";
    static final String NAV_REPORTS = "reports";

    private UiModel() {
        // Helper holder.
    }

    /** The signed-in member, or null on the login page and error pages served to anonymous callers. */
    static AuthenticatedMember currentMember() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof AuthenticatedMember member ? member : null;
    }

    static void addChrome(Model model) {
        apply(model::addAttribute);
    }

    /**
     * The same attributes onto a {@link ModelMap}, which is what a
     * {@code ModelAndView} built by an exception handler carries.
     */
    static void addChrome(ModelMap model) {
        apply(model::addAttribute);
    }

    private static void apply(BiConsumer<String, Object> sink) {
        AuthenticatedMember member = currentMember();

        sink.accept("authenticated", member != null);
        // The email, not the household id: PRD §9 asks that internal
        // identifiers stay out of what is rendered, and the email is what the
        // member typed to get here.
        sink.accept("currentMemberEmail", member == null ? null : member.email());
        sink.accept("currentMemberIsAdmin", member != null && member.isAdmin());
    }
}
