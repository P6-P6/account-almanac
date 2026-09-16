package com.accountalmanac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One tracked account, keyed by the client's account hash.
 *
 * <p>Everything here is plain text. An earlier revision kept AES-GCM
 * encrypted login credentials; that was removed in favour of
 * {@link #loginLabel}, a user-supplied nickname for identifying which
 * login an account belongs to. Credential-storing plugins are barred from
 * the plugin hub, and a label is all the tracker actually needs.
 */
class AccountRecord
{
	long accountHash;

	/** In-game display name, captured automatically on login. */
	String displayName = "";

	/**
	 * Account name as reported by the Jagex launcher, captured automatically.
	 *
	 * <p>Only ever the launcher's name. The client's own login-screen field is
	 * deliberately not read, because on the classic login flow it holds
	 * whatever was typed to log in - often an email address. Blank on a
	 * non-launcher login, and never guessed from the display name.
	 *
	 * <p>Files written before that change can still hold a value captured the
	 * old way. Nothing rewrites them, so an existing entry stays as it was
	 * until the account next logs in through the launcher.
	 */
	String loginName = "";

	/**
	 * User-editable label identifying the login this account sits under -
	 * an email alias, "main", "iron 3", whatever. Never a password.
	 */
	String loginLabel = "";

	/**
	 * {@code AccountType.name()} as reported by the client on login, e.g.
	 * {@code "IRONMAN"}. Empty until the account has logged in at least once
	 * under this feature. See {@link AccountTypeBadge} for display.
	 */
	String accountType = "";

	String note = "";

	/**
	 * User-assigned grouping - "Main", "Pure", "Zerker", or anything the user
	 * types. Stored as free text rather than an enum so a custom grouping
	 * needs no code change; see {@link AccountCategory} for the presets.
	 */
	String category = "";

	/**
	 * Excludes this account from wealth totals, item aggregates and stat
	 * rollups without deleting anything it has recorded.
	 *
	 * <p>The account stays fully visible in its own right and keeps updating
	 * on login - this only removes it from the cross-account numbers, which is
	 * the point: a mule counted twice, or a friend's account tracked for
	 * reference, distorts every total it appears in.
	 */
	boolean hidden;

	/**
	 * Marked as banned by the user.
	 *
	 * <p>Set manually. The client exposes no login-failure reason, and a
	 * rejected login never reaches a logged-in state, so there is nothing to
	 * detect it from automatically.
	 */
	boolean banned;

	/** When {@link #banned} was set, for the "recheck this" prompt. */
	long bannedAt;

	/**
	 * Time played, as reported by the game's own account summary.
	 *
	 * <p>Read from {@code VarbitID.ACCOUNT_SUMMARY_DISPLAY_PLAYTIME}, which
	 * is what the in-game Account Summary screen displays. The game does not
	 * document its unit; it is treated as minutes, which is what the displayed
	 * figure is consistent with. 0 means never captured - the varp is only
	 * populated once the account has loaded.
	 *
	 * <p>Not derived from session timing on purpose. Counting ticks would only
	 * ever measure time spent in this client, which is a different and much
	 * less interesting number than the account's actual lifetime.
	 */
	int playtimeMinutes;

	/**
	 * Quest points earned, as reported by the game.
	 *
	 * <p>Read from {@code VarPlayerID.QP}. 0 means either a genuinely
	 * questless account or one that has not logged in since this started
	 * being captured; {@link #questPointsMax} tells them apart, since it is
	 * only ever non-zero once a capture has happened.
	 */
	int questPoints;

	/**
	 * Quest points available in the game at the time of capture, from
	 * {@code VarbitID.QP_MAX}.
	 *
	 * <p>Stored per account rather than as one global constant because it
	 * rises with every quest release, and accounts are captured at different
	 * times. Showing a stale total beside a fresh one would be wrong in a way
	 * the user cannot see; keeping each account's own denominator means the
	 * fraction always reflects what the game said when that account was read.
	 */
	int questPointsMax;

	/**
	 * The counters the game's own Account Summary screen shows, each as
	 * done-out-of-total. Zero totals mean never captured, which is why every
	 * label below shows a dash rather than "0/0".
	 *
	 * <p>Quests and the collection log come from vars and so update on every
	 * login. Achievement diaries and combat tasks have no whole-account var -
	 * the game keeps them per region and per boss - so those two are read from
	 * the Account Summary screen when it is opened.
	 */
	int questsCompleted;
	int questsTotal;
	int achievementsCompleted;
	int achievementsTotal;
	int combatTasksCompleted;
	int combatTasksTotal;
	int collectionsLogged;
	int collectionsTotal;

	/** Total value of {@link #bankItems} as of {@link #lastUpdated}. */
	long bankValue;
	long lastUpdated;

	/**
	 * When this account was last logged into, set on every login regardless
	 * of whether the bank was ever opened. Kept separate from
	 * {@link #lastUpdated}, which only advances when a bank snapshot is
	 * taken - an account you log into but don't bank on would otherwise
	 * never move in a "most recent" sort, even though you were just on it.
	 */
	long lastLoginAt;

	// Skill.name() -> real (unboosted) level, e.g. "ATTACK" -> 75.
	Map<String, Integer> skillLevels = new HashMap<>();

	/**
	 * Skill.name() -> experience. Max XP is 200,000,000, which fits an int
	 * comfortably; the totals below still widen to long because 24 skills at
	 * the cap would overflow one.
	 */
	Map<String, Integer> skillXp = new HashMap<>();
	int combatLevel;
	long lastSnapshotAt;

	/**
	 * Last-seen bank contents. Only populated when the bank has actually
	 * been opened on this account - the client cannot read a closed bank.
	 * Empty means "never seen", which is distinct from a genuinely empty
	 * bank; check {@link #lastSnapshotAt} to tell them apart.
	 */
	List<BankItem> bankItems = new ArrayList<>();

	/** GE slots 0-7 as last reported. Includes empty slots. */
	List<GrandExchangeRecord> geOffers = new ArrayList<>();

	/** Gson leaves final/absent collections null on older files. */
	void normalise()
	{
		if (skillLevels == null)
		{
			skillLevels = new HashMap<>();
		}
		if (skillXp == null)
		{
			skillXp = new HashMap<>();
		}
		if (bankItems == null)
		{
			bankItems = new ArrayList<>();
		}
		else
		{
			// The game stores a bank placeholder as the item with a quantity
			// of zero. Nothing is actually held, so it is dropped here rather
			// than left for every view, export and count to remember.
			bankItems.removeIf(item -> item == null || item.quantity <= 0);
		}
		if (geOffers == null)
		{
			geOffers = new ArrayList<>();
		}
		if (displayName == null)
		{
			displayName = "";
		}
		if (loginLabel == null)
		{
			loginLabel = "";
		}
		if (loginName == null)
		{
			loginName = "";
		}
		if (accountType == null)
		{
			accountType = "";
		}
		if (note == null)
		{
			note = "";
		}
		if (category == null)
		{
			category = "";
		}
	}

	/**
	 * Whether this account contributes to cross-account totals.
	 *
	 * <p>Banned accounts still count by default - a banned account's bank is
	 * gone in practice, but the user marked it banned for their own
	 * bookkeeping and may still want the historical figure. Excluding it is a
	 * separate, explicit choice via {@link #hidden}.
	 */
	boolean countsTowardTotals()
	{
		return !hidden;
	}

	/**
	 * Grouping shown in the UI: the user's category, falling back to the
	 * ironman type the client reported, then to nothing.
	 */
	String categoryLabel()
	{
		if (category != null && !category.isEmpty())
		{
			return category;
		}
		String suggested = AccountCategory.suggestFor(accountType);
		return suggested.isEmpty() ? "" : suggested;
	}

	/** Coins tied up in outstanding GE offers. */
	long geValue()
	{
		long total = 0L;
		for (GrandExchangeRecord offer : geOffers)
		{
			total += offer.committedValue();
		}
		return total;
	}

	/** Bank value plus GE escrow - what the account is actually worth. */
	long totalWealth()
	{
		return bankValue + geValue();
	}

	/**
	 * Whether quest points have ever been read for this account.
	 *
	 * <p>Keyed on the maximum rather than the score, because a real account
	 * can sit on zero quest points indefinitely, while the maximum is never
	 * zero once the game has reported it.
	 */
	boolean hasQuestPoints()
	{
		return questPointsMax > 0;
	}

	/** Quest points as {@code earned/available}, or a dash when never captured. */
	String questPointsLabel()
	{
		return hasQuestPoints() ? questPoints + "/" + questPointsMax : "-";
	}

	/** {@code done/total}, or a dash when the total has never been read. */
	private static String fraction(int done, int total)
	{
		return total > 0 ? done + "/" + total : "-";
	}

	String questsLabel()
	{
		return fraction(questsCompleted, questsTotal);
	}

	String achievementsLabel()
	{
		return fraction(achievementsCompleted, achievementsTotal);
	}

	String combatTasksLabel()
	{
		return fraction(combatTasksCompleted, combatTasksTotal);
	}

	String collectionsLabel()
	{
		return fraction(collectionsLogged, collectionsTotal);
	}

	boolean hasBankSnapshot()
	{
		return lastSnapshotAt > 0L;
	}

	/** Stacks actually held. Placeholders sit at zero and do not count. */
	int bankStackCount()
	{
		int stacks = 0;
		for (BankItem item : bankItems)
		{
			if (item != null && item.quantity > 0)
			{
				stacks++;
			}
		}
		return stacks;
	}

	/** Most recent thing that happened to this account - a login or a bank snapshot, whichever is later. */
	long lastActivityAt()
	{
		return Math.max(lastLoginAt, lastUpdated);
	}

	/** Sum of every real skill level, the in-game "total level". */
	int totalLevel()
	{
		int total = 0;
		for (Integer level : skillLevels.values())
		{
			if (level != null)
			{
				total += level;
			}
		}
		return total;
	}

	/** Real level for a Skill.name(), or 0 if never seen. */
	int skillLevel(String skillName)
	{
		Integer level = skillLevels.get(skillName);
		return level == null ? 0 : level;
	}

	/** Experience for a Skill.name(), or 0 if never seen. */
	int skillXpFor(String skillName)
	{
		Integer xp = skillXp.get(skillName);
		return xp == null ? 0 : xp;
	}

	/** Sum of experience across every skill. */
	long totalXp()
	{
		long total = 0L;
		for (Integer xp : skillXp.values())
		{
			if (xp != null)
			{
				total += xp;
			}
		}
		return total;
	}

	/**
	 * The real name, always. This is the persistence-safe accessor: it is what
	 * gets written into logged Grand Exchange events, so it must never be
	 * masked or the user's own records would be corrupted by a display
	 * setting. UI code wants {@code NameMasker.display} instead.
	 */
	String label()
	{
		if (displayName != null && !displayName.isEmpty())
		{
			return displayName;
		}
		if (loginLabel != null && !loginLabel.isEmpty())
		{
			return loginLabel;
		}
		return "Account " + Long.toHexString(accountHash);
	}
}
