package com.accountalmanac;

import java.awt.Color;
import net.runelite.client.ui.ColorScheme;

/**
 * Maps a login age onto the user's configured colours.
 *
 * <p>Kept in one place so the sidebar, the accounts table and the reminder
 * list cannot drift apart on where the boundaries sit. The thresholds
 * themselves are settings, defaulting to the requested green under 7 days,
 * yellow from 7, red from 12.
 */
final class LoginAgeColours
{
	private LoginAgeColours()
	{
	}

	static Color forTimestamp(long epochMillis, long now, AccountAlmanacConfig config)
	{
		return forStatus(LoginAge.statusOf(epochMillis, now,
			config.warnAfterDays(), config.staleAfterDays()), config);
	}

	static Color forStatus(LoginAge.Status status, AccountAlmanacConfig config)
	{
		switch (status)
		{
			case FRESH:
				return config.freshLoginColour();
			case WARNING:
				return config.warningLoginColour();
			case STALE:
				return config.staleLoginColour();
			case NEVER:
			default:
				// Never logged in is not a staleness problem, it is an absence
				// of data - colouring it red would read as an alarm.
				return ColorScheme.LIGHT_GRAY_COLOR;
		}
	}
}
