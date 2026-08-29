package com.householdledger.web.ui;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the layout's chrome attributes on every UI page's model, so no
 * controller has to remember to.
 *
 * <p>Scoped by {@code basePackages} to the UI. An unscoped advice would also
 * attach to the REST controllers, adding attributes to responses that have no
 * model at all.
 */
@ControllerAdvice(basePackages = "com.householdledger.web.ui")
class UiLayoutAdvice {

    @ModelAttribute
    void chrome(Model model) {
        UiModel.addChrome(model);
    }
}
