package com.accountalmanac;

import java.awt.Color;

/**
 * Item stack quantities exactly as the game renders them.
 *
 * <pre>
 *   0 to 99,999                  yellow   precise      99999
 *   100,000 to 9,999,999         white    truncated K  100K, 9999K
 *   10,000,000 and above         green    truncated M  10M, 2147M
 * </pre>
 *
 * <p>Truncated, not rounded: 9,999,999 shows as {@code 9999K}, not
 * {@code 10000K}. That is what the game does, and rounding up across a
 * threshold would show a stack as having crossed a boundary it has not.
 *
 * <p>This is deliberately not routed through the user's number-format setting.
 * It is a reproduction of a fixed game convention - the colour is as much a
 * part of it as the digits - so it stays put while everything else follows the
 * chosen format. The exact figure is always available on the tooltip.
 */
final class StackFormat
{
	private static final long WHITE_FROM = 100_000L;
	private static final long GREEN_FROM = 10_000_000L;

	/** The game's own stack colours. */
	private static final Color YELLOW = new Color(255, 255, 0);
	private static final Color WHITE = new Color(255, 255, 255);
	private static final Color GREEN = new Color(0, 255, 0);

	private StackFormat()
	{
	}

	static String text(long amount)
	{
		if (amount < WHITE_FROM)
		{
			return Long.toString(amount);
		}
		if (amount < GREEN_FROM)
		{
			return (amount / 1_000L) + "K";
		}
		// The game's own range stops at int max. A cross-account total can pass
		// it, so this keeps counting in millions rather than breaking there -
		// the alternative would be a wrong number at exactly the point the
		// figure gets interesting.
		return (amount / 1_000_000L) + "M";
	}

	static Color colour(long amount)
	{
		if (amount < WHITE_FROM)
		{
			return YELLOW;
		}
		if (amount < GREEN_FROM)
		{
			return WHITE;
		}
		return GREEN;
	}
}
