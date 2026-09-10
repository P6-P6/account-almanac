package com.accountalmanac;

import java.util.concurrent.TimeUnit;

/**
 * A look-back window for the Stats and Wealth split tabs.
 *
 * <p>A timeframe answers "what changed over this period", which needs a
 * baseline to subtract from the present. That baseline comes from
 * {@link AccountHistory}, so a window only produces numbers once history has
 * actually accumulated across it - a 1 year window on two days of history
 * reports the two days it has, and says so, rather than inventing a year.
 *
 * <p>Public because it is a return type on the public
 * {@link AccountAlmanacConfig} interface. RuneLite implements that interface
 * with a JDK dynamic proxy, which lives in a different runtime package - a
 * package-private return type is unreachable from it and throws
 * {@code IllegalAccessError} as the plugin starts, taking the whole plugin
 * down with it.
 */
public enum Timeframe
{
	SEVEN_DAYS("Past 7 days", 7),
	THIRTY_DAYS("Past month", 30),
	NINETY_DAYS("Past 90 days", 90),
	ONE_YEAR("Past year", 365),

	/**
	 * No window - compare against the earliest snapshot on record. Kept as the
	 * default because it is the only option that is meaningful on day one.
	 */
	ALL_TIME("All time", -1);

	private final String label;
	private final int days;

	Timeframe(String label, int days)
	{
		this.label = label;
		this.days = days;
	}

	String label()
	{
		return label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	boolean isAllTime()
	{
		return days < 0;
	}

	/**
	 * Epoch millis at the start of this window, or 0 for {@link #ALL_TIME} so
	 * callers can treat "no cutoff" as "everything at or after zero" without
	 * a special case.
	 */
	long cutoffFrom(long now)
	{
		if (isAllTime())
		{
			return 0L;
		}
		return now - TimeUnit.DAYS.toMillis(days);
	}
}
