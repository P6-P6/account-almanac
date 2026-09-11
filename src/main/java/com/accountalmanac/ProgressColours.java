package com.accountalmanac;

import java.awt.Color;

/**
 * Colours a Grand Exchange offer's progress bar by how far along it is.
 *
 * <p>Banded rather than a smooth gradient. A gradient looks better in
 * isolation but reads worse in a list: two offers eight percent apart get
 * near-identical colours, so the eye has to fall back to the number and the
 * colour earns nothing. Bands turn the bar into a status at a glance - barely
 * started, halfway, nearly there, done - which is the question being asked of
 * a screen full of offers.
 *
 * <p>The ramp runs red through amber to green, which is the convention every
 * progress bar already trains people on, and it ends on the game's own green
 * so a finished offer looks finished.
 *
 * <pre>
 *   0%          dull red      nothing has traded yet
 *   1 - 24%     burnt orange  just started
 *   25 - 49%    amber         moving
 *   50 - 89%    gold          past halfway
 *   90 - 99%    yellow-green  nearly there
 *   100%        green         complete, ready to collect
 * </pre>
 */
final class ProgressColours
{
	/** Nothing has traded. Distinct from "just started" on purpose. */
	private static final Color NONE = new Color(0x8E, 0x3B, 0x2E);
	private static final Color STARTED = new Color(0xB8, 0x5C, 0x2E);
	private static final Color QUARTER = new Color(0xC8, 0x91, 0x2A);
	private static final Color HALF = new Color(0xD8, 0xB2, 0x2A);
	private static final Color NEARLY = new Color(0x9B, 0xBF, 0x3A);
	/** The game's own green, so a finished offer matches the interface. */
	private static final Color DONE = new Color(0x3C, 0xA0, 0x3C);

	private ProgressColours()
	{
	}

	/**
	 * @param fraction 0.0 to 1.0; anything outside is clamped
	 * @return the band colour for that progress
	 */
	static Color forFraction(double fraction)
	{
		if (fraction >= 1.0)
		{
			return DONE;
		}
		if (fraction <= 0.0)
		{
			return NONE;
		}
		if (fraction >= 0.90)
		{
			return NEARLY;
		}
		if (fraction >= 0.50)
		{
			return HALF;
		}
		if (fraction >= 0.25)
		{
			return QUARTER;
		}
		return STARTED;
	}

	/**
	 * Text colour that stays legible on top of {@link #forFraction}.
	 *
	 * <p>The mid bands are bright enough that white text on them is hard work,
	 * so those get black instead. Picked per band rather than computed from
	 * luminance, because the bands are fixed and a formula would only be a
	 * less predictable way of reaching the same six answers.
	 */
	static Color textOn(double fraction)
	{
		if (fraction >= 1.0 || fraction <= 0.0)
		{
			return Color.WHITE;
		}
		return fraction >= 0.25 && fraction < 0.90 ? Color.BLACK : Color.WHITE;
	}
}
