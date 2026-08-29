package com.householdledger.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The login page (PRD §FR-7).
 *
 * <p>There is no POST handler here on purpose. Spring Security's
 * {@code UsernamePasswordAuthenticationFilter} processes {@code POST /login}
 * before the request ever reaches a controller, so the credentials are
 * checked by {@code BrowserAuthenticationProvider} and never pass through
 * application code. A controller that took the password itself would be a
 * second authentication path to keep correct, and the one more likely to
 * forget something — timing-uniform comparison, session fixation, the
 * redirect to the originally requested page.
 *
 * <p>The two query flags are set by that filter and by the logout handler.
 * {@code ?error} deliberately carries no detail: whether the email was
 * unknown or the password wrong is not something the page should be able to
 * tell apart, since that is how a login form becomes an address-enumeration
 * tool.
 */
@Controller
class LoginUiController {

    @GetMapping("/login")
    String login(Model model,
                 @RequestParam(required = false) String error,
                 @RequestParam(required = false) String loggedOut) {

        // Already signed in: send them on rather than showing a form that
        // would sign them in as themselves again.
        if (UiModel.currentMember() != null) {
            return "redirect:/";
        }

        model.addAttribute("loginFailed", error != null);
        model.addAttribute("loggedOut", loggedOut != null);

        return "login";
    }
}
