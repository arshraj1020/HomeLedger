package com.householdledger.web.ui.support;

/**
 * Renders a signed amount of minor units as text for the browser.
 *
 * <p><b>Why the UI has its own formatter.</b> Money is a {@code long} of
 * paise everywhere in this application (PRD §3.3), and it stays that way
 * right up to the moment a page is rendered. A template that received the
 * raw number would have to divide by a hundred to show it, which is
 * arithmetic on money inside a template — untestable, invisible to the
 * compiler, and the one place a rounding mistake would be least likely to be
 * noticed. So the conversion happens here, once, in a pure function with
 * tests, and view models carry finished strings.
 *
 * <p><b>No floating point and no {@code BigDecimal}.</b> The whole
 * conversion is done on the decimal digits of {@code Long.toString}, which
 * also means {@link Long#MIN_VALUE} formats correctly instead of overflowing
 * the way {@code Math.abs} would.
 *
 * <p>Grouping follows the Indian convention that matches the PRD's rupee
 * amounts: the last three digits, then groups of two — 1234567 paise reads
 * {@code 12,345.67}, and 123456789 paise reads {@code 12,34,567.89}.
 */
public final class MoneyFormat {

    /** U+20B9 INDIAN RUPEE SIGN. */
    public static final String CURRENCY_SYMBOL = "\u20B9";

    private MoneyFormat() {
        // Conversion holder.
    }

    /**
     * Grouped, two decimal places, no currency symbol: {@code -12,34,567.89}.
     *
     * <p>The sign leads the number rather than wrapping it in parentheses.
     * Accountants' parentheses are ambiguous to a reader who is not one, and
     * this ledger shows negative figures often enough — a reversed
     * transaction, a refunded category — that they need to be unmistakable.
     */
    public static String format(long minorUnits) {
        boolean negative = minorUnits < 0L;
        String digits = paddedDigits(minorUnits);

        String rupees = digits.substring(0, digits.length() - 2);
        String paise = digits.substring(digits.length() - 2);

        return (negative ? "-" : "") + group(rupees) + "." + paise;
    }

    /**
     * The magnitude, formatted and unsigned: {@code format(-12345)} and
     * {@code formatMagnitude(-12345)} differ only by the leading minus.
     *
     * <p>Used for the credit column of a transaction, where the sign is
     * carried by which column the figure is in rather than by the digits.
     * Done by dropping the sign from the finished string rather than by
     * negating the number, so {@link Long#MIN_VALUE} — which has no positive
     * counterpart — formats instead of throwing.
     */
    public static String formatMagnitude(long minorUnits) {
        String formatted = format(minorUnits);
        return formatted.startsWith("-") ? formatted.substring(1) : formatted;
    }

    /** {@link #format} with the rupee sign, placed inside the minus: {@code -₹12,345.67}. */
    public static String withSymbol(long minorUnits) {
        String formatted = format(minorUnits);
        return formatted.startsWith("-")
                ? "-" + CURRENCY_SYMBOL + formatted.substring(1)
                : CURRENCY_SYMBOL + formatted;
    }

    /**
     * Ungrouped and unsymbolled: {@code -1234567.89}.
     *
     * <p>This is what goes back into a text input when a submitted form is
     * redisplayed with errors. Grouping separators would be re-parsed on the
     * next submit, and while {@link MoneyInput} accepts them, a value that
     * changes shape every time a form is rejected looks like the field is
     * mangling what was typed.
     */
    public static String forInput(long minorUnits) {
        boolean negative = minorUnits < 0L;
        String digits = paddedDigits(minorUnits);

        return (negative ? "-" : "")
                + digits.substring(0, digits.length() - 2)
                + "."
                + digits.substring(digits.length() - 2);
    }

    /**
     * The decimal digits of the magnitude, left-padded so there are always at
     * least three: one rupee digit and two paise digits. Working from the
     * string rather than from {@code Math.abs} is what makes
     * {@link Long#MIN_VALUE} safe — it has no positive counterpart.
     */
    private static String paddedDigits(long minorUnits) {
        String digits = Long.toString(minorUnits);
        if (digits.startsWith("-")) {
            digits = digits.substring(1);
        }
        return switch (digits.length()) {
            case 1 -> "00" + digits;
            case 2 -> "0" + digits;
            default -> digits;
        };
    }

    /** Last three digits, then groups of two, right to left. */
    private static String group(String rupeeDigits) {
        if (rupeeDigits.length() <= 3) {
            return rupeeDigits;
        }

        String head = rupeeDigits.substring(0, rupeeDigits.length() - 3);
        String tail = rupeeDigits.substring(rupeeDigits.length() - 3);

        StringBuilder grouped = new StringBuilder();
        int firstGroupLength = head.length() % 2 == 0 ? 2 : 1;
        grouped.append(head, 0, firstGroupLength);

        for (int i = firstGroupLength; i < head.length(); i += 2) {
            grouped.append(',').append(head, i, i + 2);
        }

        return grouped.append(',').append(tail).toString();
    }
}
