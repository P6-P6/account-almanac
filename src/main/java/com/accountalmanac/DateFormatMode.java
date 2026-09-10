package com.accountalmanac;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How dates and times are written out.
 *
 * <p>Always the machine's own local clock, never UTC or a game-server time -
 * "when did I last play this" is a question about your day, not Jagex's.
 *
 * <p>Public because it is a return type on the public
 * {@link AccountAlmanacConfig} interface, which RuneLite implements with a
 * dynamic proxy that cannot reach package-private types.
 */
public enum DateFormatMode
{
	/** {@code 2026-09-10 16:58} - unambiguous, and sorts as text. */
	ISO("2026-09-10 16:58", "yyyy-MM-dd HH:mm"),

	/** {@code SEP-10-2026} - month first. */
	MONTH_FIRST("SEP-10-2026", "MMM-dd-yyyy"),

	/** {@code 10-SEP-2026} - day first. */
	DAY_FIRST("10-SEP-2026", "dd-MMM-yyyy"),

	/** {@code SEP-10-2026 16:58} - month first, with the time. */
	MONTH_FIRST_TIME("SEP-10-2026 16:58", "MMM-dd-yyyy HH:mm"),

	/** {@code Thu 10 Sep 2026, 4:58 PM} - spelled out. */
	FRIENDLY("Thu 10 Sep 2026, 4:58 PM", "EEE d MMM yyyy, h:mm a");

	private final String label;
	private final DateTimeFormatter formatter;

	DateFormatMode(String label, String pattern)
	{
		this.label = label;
		// ROOT so month abbreviations do not change with the system locale -
		// a column that reads SEP on one machine and SET on another is worse
		// than one that is simply always English.
		this.formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
	}

	@Override
	public String toString()
	{
		return label;
	}

	/** Formats an epoch-millis timestamp, or "never" when it is unset. */
	String format(long epochMillis)
	{
		if (epochMillis <= 0L)
		{
			return "never";
		}
		String text = formatter.format(
			LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
		// Uppercase reads better for the abbreviated-month styles and matches
		// how the option is labelled.
		return this == MONTH_FIRST || this == DAY_FIRST || this == MONTH_FIRST_TIME
			? text.toUpperCase(Locale.ROOT)
			: text;
	}
}
