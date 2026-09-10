package com.accountalmanac;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/**
 * Every user-settable option, and the reason they all live here.
 *
 * <h2>Why settings are config items and tracked data is not</h2>
 *
 * <p>The brief asked whether anything should use RuneLite's sync rather than
 * staying local. Everything on this interface is stored through
 * {@code ConfigManager}, which means it persists across restarts and travels
 * with a RuneLite account profile if one is signed in. That is right for
 * preferences: they are small, they are not sensitive, and wanting the same
 * number format on a second PC is a reasonable expectation.
 *
 * <p>Tracked data deliberately does <em>not</em> go through config, and stays
 * in {@code .runelite/accountalmanac/}:
 *
 * <ul>
 * <li><b>Size.</b> Config values are single strings in a key-value store. The
 * account file is already around 1.8&nbsp;MB across forty accounts, plus
 * snapshot history and the GE log on top. That is not what the config store is
 * for.</li>
 * <li><b>Sensitivity.</b> The data is a list of every alt the user owns, with
 * login names, banks and net worth. Preferences syncing off the machine is
 * fine; that roster is exactly the thing the user asked to keep local.</li>
 * <li><b>Recoverability.</b> Local files can be copied, versioned and restored
 * by the backup option. A synced blob cannot be rolled back by hand.</li>
 * </ul>
 *
 * <p>Per-account metadata - labels, categories, hidden and banned flags - is
 * data rather than preference and stays with the account file for the same
 * reasons, so it survives independently of any RuneLite account.
 */
@ConfigGroup(AccountAlmanacConfig.GROUP)
public interface AccountAlmanacConfig extends Config
{
	String GROUP = "accountalmanac";

	// ------------------------------------------------------------------
	// Sections
	// ------------------------------------------------------------------

	@ConfigSection(
		name = "Numbers",
		description = "How amounts are written out.",
		position = 10
	)
	String numbersSection = "numbers";

	@ConfigSection(
		name = "Colours",
		description = "Colours used for login age, gains and losses.",
		position = 20
	)
	String coloursSection = "colours";

	@ConfigSection(
		name = "Login reminders",
		description = "When an account counts as overdue for a login.",
		position = 30
	)
	String remindersSection = "reminders";

	@ConfigSection(
		name = "History and backups",
		description = "How much history is kept, and automatic local backups.",
		position = 40
	)
	String historySection = "history";

	@ConfigSection(
		name = "Grand Exchange log",
		description = "Recording of offers, sales and collections.",
		position = 50
	)
	String geSection = "ge";

	@ConfigSection(
		name = "Privacy",
		description = "Hiding account names for screenshots and screen sharing.",
		position = 55
	)
	String privacySection = "privacy";


	// ------------------------------------------------------------------
	// General
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "includeGrandExchange",
		name = "Count GE offers as wealth",
		description = "Include coins and stock tied up in outstanding Grand Exchange offers when totalling an account's wealth.",
		position = 1
	)
	default boolean includeGrandExchange()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minimumItemValue",
		name = "Hide items under (gp)",
		description = "Items whose total value across all accounts falls below this are left out of the aggregate item list. Set to 0 to show everything.",
		position = 2
	)
	default int minimumItemValue()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "maxChartSlices",
		name = "Max chart slices",
		description = "Accounts beyond this many are grouped into a single 'Other' slice so the pie stays readable. The chart's own 'Show all' button expands past this without changing the setting.",
		position = 3
	)
	@Range(min = 1, max = 60)
	default int maxChartSlices()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "viewerTheme",
		name = "Theme",
		description = "Colour scheme for this plugin's sidebar panel and viewer window. Defaults to RuneLite's own palette so it matches the client's other panels; the rest of the client is never affected.",
		position = 6
	)
	default ViewerTheme viewerTheme()
	{
		return ViewerTheme.OSRS;
	}

	@ConfigItem(
		keyName = "dateFormat",
		name = "Date format",
		description = "How dates and times are written. Always your PC's local clock.",
		position = 7
	)
	default DateFormatMode dateFormat()
	{
		return DateFormatMode.ISO;
	}

	@ConfigItem(
		keyName = "refreshPricesOnStartup",
		name = "Reprice on start-up",
		description = "Re-price every stored bank and Grand Exchange offer against current prices when the client starts. Snapshots are taken whenever a bank happened to be open, so without this the totals compare prices from different days.",
		position = 5
	)
	default boolean refreshPricesOnStartup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defaultTimeframe",
		name = "Default timeframe",
		description = "Window pre-selected on the Stats and Wealth split tabs. 'All time' is the default because it is the only one that reports anything on the first day of tracking.",
		position = 4
	)
	default Timeframe defaultTimeframe()
	{
		return Timeframe.ALL_TIME;
	}

	// ------------------------------------------------------------------
	// Numbers
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "wealthFormat",
		name = "Wealth",
		description = "How bank values, GE values and totals are written.",
		position = 11,
		section = numbersSection
	)
	default NumberFormatMode wealthFormat()
	{
		return NumberFormatMode.ABBREVIATED;
	}

	@ConfigItem(
		keyName = "gpChangeFormat",
		name = "GP changes",
		description = "How gains and losses over a timeframe are written.",
		position = 12,
		section = numbersSection
	)
	default NumberFormatMode gpChangeFormat()
	{
		return NumberFormatMode.ABBREVIATED;
	}

	@ConfigItem(
		keyName = "statGainFormat",
		name = "Stat gains and XP",
		description = "How experience totals and gains are written.",
		position = 13,
		section = numbersSection
	)
	default NumberFormatMode statGainFormat()
	{
		return NumberFormatMode.ABBREVIATED;
	}

	@ConfigItem(
		keyName = "quantityFormat",
		name = "Item quantities",
		description = "How item counts are written.",
		position = 14,
		section = numbersSection
	)
	default NumberFormatMode quantityFormat()
	{
		return NumberFormatMode.ABBREVIATED;
	}

	@ConfigItem(
		keyName = "exactValueTooltips",
		name = "Exact value in tooltips",
		description = "Show the full, comma-separated figure in a tooltip when a cell is abbreviated.",
		position = 15,
		section = numbersSection
	)
	default boolean exactValueTooltips()
	{
		return true;
	}

	// ------------------------------------------------------------------
	// Colours
	// ------------------------------------------------------------------

	@Alpha
	@ConfigItem(
		keyName = "freshLoginColour",
		name = "Recent login",
		description = "Colour for an account logged into within the warning threshold.",
		position = 21,
		section = coloursSection
	)
	default Color freshLoginColour()
	{
		return new Color(106, 176, 106);
	}

	@Alpha
	@ConfigItem(
		keyName = "warningLoginColour",
		name = "Getting stale",
		description = "Colour once an account passes the warning threshold.",
		position = 22,
		section = coloursSection
	)
	default Color warningLoginColour()
	{
		return new Color(222, 195, 80);
	}

	@Alpha
	@ConfigItem(
		keyName = "staleLoginColour",
		name = "Stale",
		description = "Colour once an account passes the stale threshold.",
		position = 23,
		section = coloursSection
	)
	default Color staleLoginColour()
	{
		return new Color(198, 91, 91);
	}

	@Alpha
	@ConfigItem(
		keyName = "gainColour",
		name = "Gain",
		description = "Colour for a positive change over a timeframe.",
		position = 24,
		section = coloursSection
	)
	default Color gainColour()
	{
		return new Color(106, 176, 106);
	}

	@Alpha
	@ConfigItem(
		keyName = "lossColour",
		name = "Loss",
		description = "Colour for a negative change over a timeframe.",
		position = 25,
		section = coloursSection
	)
	default Color lossColour()
	{
		return new Color(198, 91, 91);
	}

	@Alpha
	@ConfigItem(
		keyName = "highValueColour",
		name = "High value",
		description = "Colour for totals and large amounts.",
		position = 26,
		section = coloursSection
	)
	default Color highValueColour()
	{
		return new Color(106, 176, 106);
	}

	// ------------------------------------------------------------------
	// Login reminders
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "warnAfterDays",
		name = "Warn after",
		description = "Days since last login before an account is coloured as getting stale.",
		position = 31,
		section = remindersSection
	)
	@Units(" days")
	@Range(min = 1, max = 365)
	default int warnAfterDays()
	{
		return 7;
	}

	@ConfigItem(
		keyName = "staleAfterDays",
		name = "Stale after",
		description = "Days since last login before an account is coloured as stale.",
		position = 32,
		section = remindersSection
	)
	@Units(" days")
	@Range(min = 1, max = 365)
	default int staleAfterDays()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "reminderAfterDays",
		name = "Remind after",
		description = "Days without a login before an account is listed as needing attention. Defaults to 14, the point at which a Grand Exchange offer's price is likely well behind the market.",
		position = 33,
		section = remindersSection
	)
	@Units(" days")
	@Range(min = 1, max = 365)
	default int reminderAfterDays()
	{
		return 14;
	}

	@ConfigItem(
		keyName = "remindOnlyWithOffers",
		name = "Only with live offers",
		description = "Restrict reminders to accounts that actually have outstanding Grand Exchange offers.",
		position = 34,
		section = remindersSection
	)
	default boolean remindOnlyWithOffers()
	{
		return false;
	}

	// ------------------------------------------------------------------
	// History and backups
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "snapshotIntervalHours",
		name = "Snapshot every",
		description = "Smallest gap between retained history snapshots. 24 hours gives the 7 day window seven distinct points to compare; shorter intervals give finer charts and a bigger file.",
		position = 41,
		section = historySection
	)
	@Units(" hours")
	@Range(min = 1, max = 168)
	default int snapshotIntervalHours()
	{
		return 24;
	}

	@ConfigItem(
		keyName = "autoBackup",
		name = "Automatic backups",
		description = "Periodically copy the tracked data files to a timestamped folder inside the plugin's own directory.",
		position = 42,
		section = historySection
	)
	default boolean autoBackup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "backupIntervalDays",
		name = "Backup every",
		description = "Days between automatic backups.",
		position = 43,
		section = historySection
	)
	@Units(" days")
	@Range(min = 1, max = 90)
	default int backupIntervalDays()
	{
		return 7;
	}

	@ConfigItem(
		keyName = "backupsToKeep",
		name = "Backups to keep",
		description = "Older backups beyond this many are deleted, oldest first.",
		position = 44,
		section = historySection
	)
	@Range(min = 1, max = 50)
	default int backupsToKeep()
	{
		return 8;
	}

	// ------------------------------------------------------------------
	// Grand Exchange log
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "logGeEvents",
		name = "Record GE events",
		description = "Log offers started, finished and cancelled, and items and coins collected.",
		position = 51,
		section = geSection
	)
	default boolean logGeEvents()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxGeEvents",
		name = "Events to keep",
		description = "Oldest events beyond this many are dropped. Purchase and sale history read from this log, so a larger number reaches further back. Roughly 235 bytes each, so 100,000 is about 22 MB and the 500,000 ceiling about 112 MB. The default suits one or two accounts for years; raise it if you play many accounts heavily. The whole log is held in memory and rewritten on every save, which is what sets the ceiling.",
		position = 52,
		section = geSection
	)
	@Range(min = 500, max = 500000)
	default int maxGeEvents()
	{
		return 100000;
	}

	// ------------------------------------------------------------------
	// Privacy
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "namePrivacy",
		name = "Account names",
		description = "Mask account names in the panel and viewer, for screenshots and screen sharing. Display only - nothing is written back, the stored data and the event log keep the real names, and switching back to 'Show real names' restores them immediately.",
		position = 56,
		section = privacySection
	)
	default NamePrivacy namePrivacy()
	{
		return NamePrivacy.REAL;
	}


}
