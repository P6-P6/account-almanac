package com.accountalmanac;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * How often the Grand Exchange log is copied into a dated archive.
 *
 * <p>The log keeps a fixed number of events and drops the oldest past that,
 * which is what stops the file growing without limit. An archive is a plain
 * copy taken before that happens, so a year-old purchase price is still
 * recoverable from disk even though the live log no longer reaches back.
 *
 * <p>Public because it is a return type on the public
 * {@link AccountAlmanacConfig} interface, which RuneLite implements with a
 * dynamic proxy from another package - see {@link NamePrivacy} for what a
 * package-private type does there.
 */
public enum GeLogArchive
{
	OFF("Off"),
	WEEKLY("Weekly"),
	FORTNIGHTLY("Every 2 weeks"),
	MONTHLY("Monthly"),
	QUARTERLY("Every 3 months"),
	HALF_YEARLY("Every 6 months"),
	YEARLY("Yearly");

	private final String label;

	GeLogArchive(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	boolean isOn()
	{
		return this != OFF;
	}

	/**
	 * Name of the period a date falls in, from the machine's own clock - e.g.
	 * {@code 2026-09} for September on the monthly setting.
	 *
	 * <p>One archive is written per period, the first time the client runs
	 * inside it. That means a monthly archive holds the log as it stood at the
	 * end of the previous month, which is the point of it, and that a client
	 * left closed over a month end still archives when it next opens rather
	 * than missing the period entirely.
	 *
	 * <p>The keys sort chronologically as text, which is what orders the
	 * folder and decides which archives are the oldest when pruning.
	 */
	String periodKey(LocalDate date)
	{
		WeekFields weeks = WeekFields.ISO;
		switch (this)
		{
			case WEEKLY:
				return String.format(Locale.ROOT, "%d-W%02d",
					date.get(weeks.weekBasedYear()), date.get(weeks.weekOfWeekBasedYear()));
			case FORTNIGHTLY:
			{
				int fortnight = (date.get(weeks.weekOfWeekBasedYear()) + 1) / 2;
				return String.format(Locale.ROOT, "%d-F%02d", date.get(weeks.weekBasedYear()), fortnight);
			}
			case MONTHLY:
				return String.format(Locale.ROOT, "%d-%02d", date.getYear(), date.getMonthValue());
			case QUARTERLY:
				return String.format(Locale.ROOT, "%d-Q%d", date.getYear(), (date.getMonthValue() + 2) / 3);
			case HALF_YEARLY:
				return String.format(Locale.ROOT, "%d-H%d", date.getYear(), date.getMonthValue() <= 6 ? 1 : 2);
			case YEARLY:
				return String.format(Locale.ROOT, "%d", date.getYear());
			default:
				return "";
		}
	}
}
