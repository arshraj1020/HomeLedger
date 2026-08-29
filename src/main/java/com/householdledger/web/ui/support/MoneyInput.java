package com.householdledger.web.ui.support;

/**
 * Parses what a member typed into an amount field into {@code long} minor
 * units.
 *
 * <p><b>Why not {@code BigDecimal} or a {@code double}.</b> PRD §3.3 and
 * §10 are unambiguous that money is an integer of minor units and that
 * floating point is never to touch it. Routing input through
 * {@code Double.parseDouble} would put a binary fraction between the member's
 * keystrokes and the ledger — {@code 0.1 + 0.2} territory, on the one number
 * that must never be approximate. {@code BigDecimal} would be exact but would
 * introduce a second money representation into the codebase for the sake of
 * one text field. So the parse is done on the characters: split at the
 * decimal point, and combine with {@link Math#multiplyExact} and
 * {@link Math#addExact}, which throw rather than wrap.
 *
 * <p><b>What is accepted.</b> An optional sign, digits with optional grouping
 * commas or spaces, an optional rupee symbol, and at most two decimal places
 * after a {@code .}. {@code 1,234.5} is 1234 rupees 50 paise, not 5.
 * {@code 1234.} and {@code .50} are accepted as 1234.00 and 0.50.
 *
 * <p><b>What is rejected, deliberately.</b> Three or more decimal places
 * raise an error rather than rounding: silently turning {@code 10.005} into
 * {@code 10.00} or {@code 10.01} would be the application deciding what
 * someone meant about money. Everything else non-numeric is rejected too, so
 * a typo cannot become a plausible number.
 *
 * <p>Every failure is an {@link IllegalArgumentException} whose message is
 * safe to show a member: it describes the input rule, never the internals.
 */
public final class MoneyInput {

    private MoneyInput() {
        // Parser holder.
    }

    /**
     * @throws IllegalArgumentException if the text is blank or is not a
     *         well-formed amount with at most two decimal places
     */
    public static long parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Enter an amount");
        }

        String cleaned = strip(text);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Enter an amount");
        }

        boolean negative = false;
        if (cleaned.charAt(0) == '-' || cleaned.charAt(0) == '+') {
            negative = cleaned.charAt(0) == '-';
            cleaned = cleaned.substring(1);
        }

        int point = cleaned.indexOf('.');
        String wholePart = point < 0 ? cleaned : cleaned.substring(0, point);
        String fractionPart = point < 0 ? "" : cleaned.substring(point + 1);

        if (point != cleaned.lastIndexOf('.')) {
            throw new IllegalArgumentException("Enter an amount like 1234.50");
        }
        if (wholePart.isEmpty() && fractionPart.isEmpty()) {
            throw new IllegalArgumentException("Enter an amount");
        }
        requireDigits(wholePart);
        requireDigits(fractionPart);
        if (fractionPart.length() > 2) {
            throw new IllegalArgumentException(
                    "Amounts have at most two decimal places, so 10.50 is fine but 10.505 is not");
        }

        long rupees = wholePart.isEmpty() ? 0L : parseDigits(wholePart);
        long paise = switch (fractionPart.length()) {
            case 0 -> 0L;
            case 1 -> parseDigits(fractionPart) * 10L;
            default -> parseDigits(fractionPart);
        };

        long magnitude;
        try {
            magnitude = Math.addExact(Math.multiplyExact(rupees, 100L), paise);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("That amount is too large to record");
        }

        return negative ? Math.negateExact(magnitude) : magnitude;
    }

    /**
     * The same parse, additionally requiring a strictly positive result.
     *
     * <p>Simple and split entry take a magnitude and derive the signs
     * themselves (PRD §FR-3), so a negative here is not a small amount — it
     * is a member trying to express direction in a field that does not carry
     * it, and would silently reverse the entry.
     */
    public static long parsePositive(String text) {
        long amount = parse(text);
        if (amount <= 0L) {
            throw new IllegalArgumentException("Enter an amount greater than zero");
        }
        return amount;
    }

    /** Removes grouping separators, spaces and the rupee sign; keeps digits, sign and point. */
    private static String strip(String text) {
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean noise = c == ',' || c == ' ' || c == '\t'
                    || c == '\u00A0' || c == '\u20B9' || c == '_';
            if (!noise) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private static void requireDigits(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Enter an amount like 1234.50");
            }
        }
    }

    /**
     * ASCII digits only, overflow-checked. {@code Long.parseLong} would do
     * the same job but would also accept a leading sign in the middle of the
     * number, which {@link #requireDigits} has already ruled out and which
     * this makes structurally impossible.
     */
    private static long parseDigits(String digits) {
        long value = 0L;
        try {
            for (int i = 0; i < digits.length(); i++) {
                value = Math.addExact(Math.multiplyExact(value, 10L), digits.charAt(i) - '0');
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("That amount is too large to record");
        }
        return value;
    }
}
