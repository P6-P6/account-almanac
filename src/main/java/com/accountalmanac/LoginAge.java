package com.accountalmanac;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * How long ago an account was last logged into, and how alarming that is.
 *
 * <p>Timestamps are recorded with {@code System.currentTimeMillis()} and
 * rendered through the JVM's default zone, so the date and time shown are the
 * PC's own local clock rather than UTC or a game-server time.
 */
final class LoginAge
{
	/** e.g. {@code "2026-09-03 18:49"} - sortable as text, unambiguous. */
	private static final DateTimeFormatter EXACT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/** e.g. {@code "Wed 3 Sep 2026, 6:49 PM"} - for the prominent header. */
	private static final DateTimeFormatter FRIENDLY =
		DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a");

	private LoginAge()
	{
	}

	/**
	 * Severity of an account's login age. Thresholds are configurable; these
	 * names describe the role, not a fixed day count.
	 */
	enum Status
	{
		/** Never logged in under this tracker - no date to age against. */
		NEVER,
		/** Within the fresh window. */
		FRESH,
		/** Past the warning threshold. */
		WARNING,
		/** Past the stale threshold. */
		STALE
	}

	/**
	 * Whole days elapsed since {@code epochMillis}, or -1 if it is unset.
	 *
	 * <p>Deliberately floor division on elapsed milliseconds rather than a
	 * calendar-day difference: "6 days" here means a full 144 hours have
	 * passed, which is what a staleness threshold should mean. A calendar
	 * difference would call 11pm yesterday to 1am today "1 day".
	 */
	static long daysSince(long epochMillis, long now)
	{
		if (epochMillis <= 0L)
		{
			return -1L;
		}
		long elapsed = now - epochMillis;
		if (elapsed < 0L)
		{
			// Clock moved backwards, or a file written on a machine ahead of
			// this one. Treat as "today" rather than reporting negative days.
			return 0L;
		}
		return TimeUnit.MILLISECONDS.toDays(elapsed);
	}

	/**
	 * @param warnAfterDays  first day that counts as a warning (default 7)
	 * @param staleAfterDays first day that counts as stale (default 12)
	 */
	static Status statusOf(long epochMillis, long now, int warnAfterDays, int staleAfterDays)
	{
		long days = daysSince(epochMillis, now);
		if (days < 0L)
		{
			return Status.NEVER;
		}
		if (days >= staleAfterDays)
		{
			return Status.STALE;
		}
		if (days >= warnAfterDays)
		{
			return Status.WARNING;
		}
		return Status.FRESH;
	}

	/**
	 * The user's chosen date style, or {@code "never"} when unset.
	 *
	 * <p>Set by {@link #applyConfig}. Held statically for the same reason the
	 * number formats are - threading a formatter through every renderer and
	 * table model is a wide change in service of one setting.
	 */
	private static volatile DateFormatMode mode = DateFormatMode.ISO;

	static void applyConfig(AccountAlmanacConfig config)
	{
		if (config != null && config.dateFormat() != null)
		{
			mode = config.dateFormat();
		}
	}

	/** A timestamp in the user's chosen style, or {@code "never"} when unset. */
	static String exact(long epochMillis)
	{
		return mode.format(epochMillis);
	}

	/** Always {@code "2026-09-03 18:49"}, whatever the setting - for tooltips. */
	static String iso(long epochMillis)
	{
		if (epochMillis <= 0L)
		{
			return "never";
		}
		return EXACT.format(local(epochMillis));
	}

	/** {@code "Wed 3 Sep 2026, 6:49 PM"}, or {@code "never"} when unset. */
	static String friendly(long epochMillis)
	{
		if (epochMillis <= 0L)
		{
			return "never";
		}
		return FRIENDLY.format(local(epochMillis));
	}

	/** {@code "3 days ago"} / {@code "today"} / {@code "never"}. */
	static String describeAge(long epochMillis, long now)
	{
		long days = daysSince(epochMillis, now);
		if (days < 0L)
		{
			return "never";
		}
		if (days == 0L)
		{
			return "today";
		}
		if (days == 1L)
		{
			return "yesterday";
		}
		return days + " days ago";
	}

	private static LocalDateTime local(long epochMillis)
	{
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
	}
}
