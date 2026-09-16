package com.accountalmanac;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * How a number is rendered. Selectable per value category (wealth, gp change,
 * stat gain, plain counts) so the same amount can read as {@code "237.7K"} in
 * a cramped chart legend and {@code "237,700"} in a table the user is
 * actually reconciling against the game.
 *
 * <p>{@link #ABBREVIATED} deliberately keeps one decimal place below the
 * billion mark. Rounding 237,700 to a bare {@code "237K"} loses the digit the
 * user is usually looking at, which is exactly the complaint that drove the
 * switch away from RuneLite's {@code quantityToStackSize} formatting.
 *
 * <p>Public because it is a return type on the public
 * {@link AccountAlmanacConfig} interface. RuneLite implements that interface
 * with a JDK dynamic proxy, which lives in a different runtime package - a
 * package-private return type is unreachable from it and throws
 * {@code IllegalAccessError} as the plugin starts, taking the whole plugin
 * down with it.
 */
public enum NumberFormatMode
{
	/** {@code 237.7K} - short, for legends and narrow columns. */
	ABBREVIATED("Abbreviated"),

	/** {@code 237,700} - full precision, thousands separated. */
	FULL_WITH_COMMAS("With commas"),

	/** {@code 237700} - full precision, no separators, easy to copy out. */
	FULL_PLAIN("Plain digits");

	private final String label;

	NumberFormatMode(String label)
	{
		this.label = label;
	}

	String label()
	{
		return label;
	}

	@Override
	public String toString()
	{
		// Drives the text shown in RuneLite's config dropdown and the
		// viewer's own combo boxes, so both read the same.
		return label;
	}

	String format(long amount)
	{
		switch (this)
		{
			case FULL_WITH_COMMAS:
				return NumberFormat.getIntegerInstance(Locale.ROOT).format(amount);
			case FULL_PLAIN:
				return Long.toString(amount);
			case ABBREVIATED:
			default:
				return abbreviate(amount);
		}
	}

	/**
	 * Two decimals at billions, one below. Negatives are handled by sign
	 * rather than by {@code Math.abs}, because {@code Long.MIN_VALUE} has no
	 * positive counterpart and would silently stay negative.
	 */
	private static String abbreviate(long amount)
	{
		if (amount >= 1_000_000_000L || amount <= -1_000_000_000L)
		{
			return String.format(Locale.ROOT, "%.2fB", amount / 1_000_000_000.0);
		}
		if (amount >= 1_000_000L || amount <= -1_000_000L)
		{
			return String.format(Locale.ROOT, "%.1fM", amount / 1_000_000.0);
		}
		if (amount >= 1_000L || amount <= -1_000L)
		{
			return String.format(Locale.ROOT, "%.1fK", amount / 1_000.0);
		}
		return Long.toString(amount);
	}
}
