package com.accountalmanac;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Number and time formatting, driven by the user's display settings.
 *
 * <p>The chosen modes are held in static fields rather than being threaded
 * through every panel as a parameter. That is a deliberate trade: the
 * alternative was passing a formatter into {@code PieChartPanel},
 * {@code AccountRowPanel}, {@code StatsGridPanel}, every table model and every
 * cell renderer, which is a wide refactor of working display code in service of
 * a settings feature. The fields are {@code volatile} and only ever written by
 * {@link #applyConfig}, which runs on plugin start and whenever config changes;
 * everything else reads them from the Swing thread.
 */
final class Format
{
	private static volatile NumberFormatMode wealthMode = NumberFormatMode.ABBREVIATED;
	private static volatile NumberFormatMode changeMode = NumberFormatMode.ABBREVIATED;
	private static volatile NumberFormatMode xpMode = NumberFormatMode.FULL_WITH_COMMAS;
	private static volatile NumberFormatMode quantityMode = NumberFormatMode.ABBREVIATED;

	private Format()
	{
	}

	/**
	 * Adopts the user's formatting preferences. Called on start-up and on every
	 * config change, so the tables re-render in the newly chosen style without
	 * a client restart.
	 */
	static void applyConfig(AccountAlmanacConfig config)
	{
		if (config == null)
		{
			return;
		}
		wealthMode = orDefault(config.wealthFormat(), NumberFormatMode.ABBREVIATED);
		changeMode = orDefault(config.gpChangeFormat(), NumberFormatMode.ABBREVIATED);
		xpMode = orDefault(config.statGainFormat(), NumberFormatMode.FULL_WITH_COMMAS);
		quantityMode = orDefault(config.quantityFormat(), NumberFormatMode.ABBREVIATED);
	}

	private static NumberFormatMode orDefault(NumberFormatMode mode, NumberFormatMode fallback)
	{
		return mode == null ? fallback : mode;
	}

	/** Coin amount in the user's wealth style, e.g. {@code "1.4M gp"}. */
	static String gp(long gp)
	{
		return wealthMode.format(gp) + " gp";
	}

	/** Coin amount with no unit suffix, for columns headed "gp" already. */
	static String gpBare(long gp)
	{
		return wealthMode.format(gp);
	}

	/**
	 * A signed change, e.g. {@code "+1.4M"} / {@code "-320.5K"}. Zero is
	 * rendered without a sign, since "+0" reads as a gain that did not happen.
	 */
	static String gpChange(long delta)
	{
		if (delta == 0L)
		{
			return "0";
		}
		String magnitude = changeMode.format(Math.abs(delta));
		return (delta > 0L ? "+" : "-") + magnitude;
	}

	/** A signed experience change, e.g. {@code "+1,204,551"}. */
	static String xpChange(long delta)
	{
		if (delta == 0L)
		{
			return "0";
		}
		String magnitude = xpMode.format(Math.abs(delta));
		return (delta > 0L ? "+" : "-") + magnitude;
	}

	/** Bare count in the user's quantity style, e.g. {@code "1.4M"}. */
	static String quantity(long amount)
	{
		return quantityMode.format(amount);
	}

	/**
	 * Full grouped number regardless of settings, e.g. {@code "1,438,204"}.
	 * Used for tooltips, where the whole point is to show what an abbreviated
	 * cell is hiding.
	 */
	static String exact(long amount)
	{
		return NumberFormat.getIntegerInstance(Locale.ROOT).format(amount);
	}

	/** Signed full grouped number, for tooltips on change cells. */
	static String exactChange(long delta)
	{
		if (delta == 0L)
		{
			return "0";
		}
		return (delta > 0L ? "+" : "-") + exact(Math.abs(delta));
	}

	static String relativeTime(long epochMillis)
	{
		if (epochMillis <= 0)
		{
			return "never updated";
		}
		Duration d = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now());
		long minutes = d.toMinutes();
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + "m ago";
		}
		long hours = d.toHours();
		if (hours < 24)
		{
			return hours + "h ago";
		}
		return d.toDays() + "d ago";
	}
}
