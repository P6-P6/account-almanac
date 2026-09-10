package com.accountalmanac;

import java.util.HashMap;
import java.util.Map;

/**
 * One account's state at one moment, kept so the Stats and Wealth split tabs
 * can answer "what changed over the past 7 days / month / 90 days / year".
 *
 * <p>Wealth and experience are both recorded, because the two tabs ask
 * different questions of the same instant and taking them from separate
 * snapshots would let a bank reprice land between them.
 */
class HistorySnapshot
{
	/** Local PC time this snapshot was taken, epoch millis. */
	long at;

	/** Bank value at {@link #at}, using the prices known then. */
	long bankValue;

	/** Coins committed to Grand Exchange offers at {@link #at}. */
	long geValue;

	long totalXp;
	int totalLevel;
	int combatLevel;

	/**
	 * Skill name to experience, so per-skill gains over a window can be
	 * computed rather than only the total.
	 *
	 * <p>This is the bulk of a snapshot's size on disk - roughly two dozen
	 * entries - which is why {@link AccountHistory} thins older snapshots out
	 * instead of keeping every one forever.
	 */
	Map<String, Integer> skillXp = new HashMap<>();

	HistorySnapshot()
	{
	}

	static HistorySnapshot of(AccountRecord record, long at)
	{
		HistorySnapshot snapshot = new HistorySnapshot();
		snapshot.at = at;
		snapshot.bankValue = record.bankValue;
		snapshot.geValue = record.geValue();
		snapshot.totalXp = record.totalXp();
		snapshot.totalLevel = record.totalLevel();
		snapshot.combatLevel = record.combatLevel;
		snapshot.skillXp = new HashMap<>(record.skillXp);
		return snapshot;
	}

	long totalWealth()
	{
		return bankValue + geValue;
	}

	int skillXpFor(String skillName)
	{
		if (skillXp == null)
		{
			return 0;
		}
		Integer xp = skillXp.get(skillName);
		return xp == null ? 0 : xp;
	}

	/**
	 * True when this snapshot carries nothing worth keeping - no wealth and no
	 * experience. Used to avoid seeding history from an account that has been
	 * seen at the login screen but never actually observed.
	 */
	boolean isEmpty()
	{
		return bankValue == 0L && geValue == 0L && totalXp == 0L;
	}

	void normalise()
	{
		if (skillXp == null)
		{
			skillXp = new HashMap<>();
		}
	}
}
