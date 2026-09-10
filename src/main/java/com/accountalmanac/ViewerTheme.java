package com.accountalmanac;

import java.awt.Color;
import net.runelite.client.ui.ColorScheme;

/**
 * Colour scheme for the standalone viewer window.
 *
 * <p>Applies to both the sidebar panel and the viewer window.
 *
 * <p>{@link #OSRS} is the default. {@link #RUNELITE} is kept first in the list
 * as the option that matches the client's other plugin panels exactly, for
 * anyone who wants the panel to disappear into its surroundings.
 *
 * <p>Public because it is a return type on the public
 * {@link AccountAlmanacConfig} interface, which RuneLite implements with a
 * dynamic proxy that cannot reach package-private types.
 */
public enum ViewerTheme
{
	/** The client's own palette - matches the sidebar and the rest of RuneLite. */
	RUNELITE("RuneLite dark",
		ColorScheme.DARK_GRAY_COLOR,
		ColorScheme.DARKER_GRAY_COLOR,
		ColorScheme.LIGHT_GRAY_COLOR,
		ColorScheme.MEDIUM_GRAY_COLOR,
		ColorScheme.BRAND_ORANGE),

	LIGHT("Light",
		new Color(244, 242, 238),
		new Color(230, 227, 220),
		new Color(34, 32, 29),
		new Color(106, 101, 93),
		new Color(154, 107, 22)),

	MIDNIGHT("Midnight blue",
		new Color(14, 20, 32),
		new Color(22, 32, 48),
		new Color(219, 228, 240),
		new Color(129, 148, 173),
		new Color(90, 169, 230)),

	/** Parchment and dark wood, the palette the game's own interfaces use. */
	OSRS("Old School browns",
		new Color(62, 53, 41),
		new Color(42, 36, 28),
		new Color(240, 230, 210),
		new Color(184, 168, 135),
		new Color(255, 255, 0)),

	/** Near-black with crimson - the colour Zamorak's kit is trimmed in. */
	ZAMORAK("Zamorak red",
		new Color(24, 14, 14),
		new Color(38, 20, 20),
		new Color(238, 222, 222),
		new Color(163, 128, 128),
		new Color(214, 64, 64)),

	/** Deep forest and moss, for Guthix. */
	GUTHIX("Guthix green",
		new Color(20, 30, 22),
		new Color(28, 42, 31),
		new Color(226, 238, 226),
		new Color(134, 163, 138),
		new Color(106, 194, 112)),

	/**
	 * White and gold, for Saradomin. Deliberately the light one of the three -
	 * a third dark blue would be hard to tell from Midnight at a glance.
	 */
	SARADOMIN("Saradomin white",
		new Color(240, 243, 248),
		new Color(222, 229, 240),
		new Color(26, 32, 44),
		new Color(94, 106, 128),
		new Color(176, 137, 26));

	private final String label;
	private final Color background;
	private final Color altBackground;
	private final Color text;
	private final Color dimText;
	private final Color accent;

	ViewerTheme(String label, Color background, Color altBackground,
		Color text, Color dimText, Color accent)
	{
		this.label = label;
		this.background = background;
		this.altBackground = altBackground;
		this.text = text;
		this.dimText = dimText;
		this.accent = accent;
	}

	@Override
	public String toString()
	{
		return label;
	}

	Color background()
	{
		return background;
	}

	/** Table headers and secondary surfaces. */
	Color altBackground()
	{
		return altBackground;
	}

	Color text()
	{
		return text;
	}

	Color dimText()
	{
		return dimText;
	}

	Color accent()
	{
		return accent;
	}

	/**
	 * Row highlight. Derived from the background rather than hardcoded, so it
	 * stays a subtle shift on a light theme instead of the near-black that a
	 * fixed dark selection colour would give.
	 */
	Color selection()
	{
		boolean dark = luminance(background) < 0.5;
		return dark ? lighten(background, 0.18f) : darken(background, 0.10f);
	}

	/** Grid lines: just off the background, in whichever direction has room. */
	Color grid()
	{
		boolean dark = luminance(background) < 0.5;
		return dark ? lighten(background, 0.08f) : darken(background, 0.08f);
	}

	private static double luminance(Color c)
	{
		return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
	}

	private static Color lighten(Color c, float amount)
	{
		return new Color(
			Math.min(255, Math.round(c.getRed() + 255 * amount)),
			Math.min(255, Math.round(c.getGreen() + 255 * amount)),
			Math.min(255, Math.round(c.getBlue() + 255 * amount)));
	}

	private static Color darken(Color c, float amount)
	{
		return new Color(
			Math.max(0, Math.round(c.getRed() - 255 * amount)),
			Math.max(0, Math.round(c.getGreen() - 255 * amount)),
			Math.max(0, Math.round(c.getBlue() - 255 * amount)));
	}
}
