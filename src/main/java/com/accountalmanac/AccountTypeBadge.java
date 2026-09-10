package com.accountalmanac;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * Display strings and colors for an {@code AccountRecord.accountType}
 * value - the raw {@code AccountType.name()} string, e.g. {@code
 * "IRONMAN"}.
 *
 * <h2>Where the icons come from</h2>
 *
 * <p>The game's own account-type sprites exist ({@code SpriteID.AccountIcons}
 * and {@code SpriteID.IronIcons}) but their constants are still unnamed
 * ({@code _0}..{@code _5}) with no documented mapping to an
 * {@code AccountType}. Guessing which index is which risks labelling a
 * Hardcore Ironman as a regular one, which is worse than no icon at all.
 *
 * <p>RuneLite itself ships the real 18x18 helm icons as ordinary resources
 * for its hiscore panel, keyed by name rather than by index. Those are used
 * here: correct art, no guesswork, and no second copy of an asset that is
 * already on the classpath. They are loaded by resource path rather than by
 * referencing the owning plugin class, so this does not compile-depend on
 * another plugin's internals.
 *
 * <p>Group ironman has no bundled icon, so those keep the text badge. Every
 * lookup falls back to text if the resource is missing, because an icon that
 * fails to load must degrade to a readable label rather than to nothing.
 */
@Slf4j
final class AccountTypeBadge
{
	/** RuneLite's own hiscore panel icons - the official helm art. */
	private static final String ICON_PATH = "/net/runelite/client/plugins/hiscore/";

	/** Loaded once and reused; a miss is cached as absent so it is not retried per repaint. */
	private static final Map<String, BufferedImage> ICONS = new HashMap<>();

	private AccountTypeBadge()
	{
	}

	/**
	 * Helm icon for an account type, or {@code null} when there is none - a
	 * normal account, a group ironman, or a resource that failed to load.
	 * Callers fall back to {@link #shortLabel}.
	 */
	static synchronized BufferedImage icon(String accountType)
	{
		String file = iconFileFor(accountType);
		if (file == null)
		{
			return null;
		}
		if (ICONS.containsKey(file))
		{
			return ICONS.get(file);
		}

		BufferedImage loaded = null;
		try (InputStream in = AccountTypeBadge.class.getResourceAsStream(ICON_PATH + file))
		{
			if (in != null)
			{
				loaded = ImageIO.read(in);
			}
			else
			{
				log.debug("Account type icon {} not present; falling back to a text badge", file);
			}
		}
		catch (IOException e)
		{
			log.debug("Could not read account type icon {}", file, e);
		}

		// Cached even when null, so a missing resource is looked up once.
		ICONS.put(file, loaded);
		return loaded;
	}

	private static String iconFileFor(String accountType)
	{
		switch (accountType)
		{
			case "IRONMAN":
				return "ironman.png";
			case "HARDCORE_IRONMAN":
				return "hardcore_ironman.png";
			case "ULTIMATE_IRONMAN":
				return "ultimate_ironman.png";
			default:
				// Group ironman variants have no bundled art.
				return null;
		}
	}

	/** "" for a normal or not-yet-known account - callers should skip the badge entirely in that case. */
	static String shortLabel(String accountType)
	{
		switch (accountType)
		{
			case "IRONMAN":
				return "IM";
			case "HARDCORE_IRONMAN":
				return "HCIM";
			case "ULTIMATE_IRONMAN":
				return "UIM";
			case "GROUP_IRONMAN":
				return "GIM";
			case "HARDCORE_GROUP_IRONMAN":
				return "HCGIM";
			default:
				return "";
		}
	}

	/** Full name for tables and tooltips. "-" when never captured. */
	static String fullLabel(String accountType)
	{
		switch (accountType)
		{
			case "NORMAL":
				return "Normal";
			case "IRONMAN":
				return "Ironman";
			case "HARDCORE_IRONMAN":
				return "Hardcore Ironman";
			case "ULTIMATE_IRONMAN":
				return "Ultimate Ironman";
			case "GROUP_IRONMAN":
				return "Group Ironman";
			case "HARDCORE_GROUP_IRONMAN":
				return "Hardcore Group Ironman";
			default:
				return "-";
		}
	}

	static Color color(String accountType)
	{
		switch (accountType)
		{
			case "IRONMAN":
				return new Color(150, 111, 51);
			case "HARDCORE_IRONMAN":
				return new Color(178, 34, 34);
			case "ULTIMATE_IRONMAN":
				return new Color(50, 50, 50);
			case "GROUP_IRONMAN":
				return new Color(46, 125, 87);
			case "HARDCORE_GROUP_IRONMAN":
				return new Color(150, 40, 40);
			default:
				return Color.GRAY;
		}
	}

	static boolean isIronmanVariant(String accountType)
	{
		return !shortLabel(accountType).isEmpty();
	}
}
