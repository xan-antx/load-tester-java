package com.elevate.analysis.stats;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Rounding and percentages shared by the analysis classes. */
public final class Numbers {

    private Numbers() {
    }

    /** Rounds half up to {@code decimals} places. */
    public static double round(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    /** {@link #round(double, int)} that passes {@code null} through. */
    public static Double round(Double value, int decimals) {
        return value == null ? null : round(value.doubleValue(), decimals);
    }

    /**
     * A number for messages, like Python's {@code :g}: at most 6 significant digits and no
     * trailing zeros ({@code 500}, not {@code 500.0}; {@code 6.66667}).
     */
    public static String plain(double value) {
        return BigDecimal.valueOf(value).round(new MathContext(6)).stripTrailingZeros().toPlainString();
    }

    /**
     * {@code part / whole * 100}, rounded.
     *
     * @return the percentage, or {@code null} when {@code whole} is unknown or zero
     */
    public static Double percent(long part, Long whole, int decimals) {
        return whole == null || whole == 0 ? null : round(part * 100.0 / whole, decimals);
    }
}
