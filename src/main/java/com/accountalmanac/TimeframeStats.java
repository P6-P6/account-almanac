package com.accountalmanac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes "what changed over this window" from stored snapshots.
 *
 * <h2>Partial windows are reported, not faked</h2>
 *
 * <p>A 1 year window asked for on two weeks of history cannot be answered.
 * Two responses would be wrong: reporting zero change (implying nothing
 * happened all year), or silently measuring from the oldest snapshot and
 * labelling it "past year" (implying a year of data exists).
 *
 * <p>So a {@link Change} carries {@link Change#covered} and
 * {@link Change#baselineAt}: the numbers are measured from the oldest snapshot
 * available, and the UI is told the window is only partially covered along with
 * the date actually being measured from. History accumulates from the moment V2
 * first runs, so the longer windows fill in over time.
 */
final class TimeframeStats
{
	private TimeframeStats()
	{
	}

	/** A before-and-after comparison for one account, or a whole roster. */
	static class Change
	{
		/** Timestamp actually measured from, 0 when there is no history at all. */
		long baselineAt;

		/**
		 * {@code true} when history reaches the full requested window. When
		 * {@code false} the deltas are still real, but they cover less time
		 * than the window's label claims.
		 */
		boolean covered;

		/** {@code true} when there is no usable baseline and every delta is 0. */
		boolean empty = true;

		long baselineWealth;
		long currentWealth;

		long baselineXp;
		long currentXp;

		int baselineTotalLevel;
		int currentTotalLevel;

		long wealthDelta()
		{
			return currentWealth - baselineWealth;
		}

		/** Percentage wealth change, or 0 when there was nothing to grow from. */
		double wealthPercent()
		{
			if (baselineWealth <= 0L)
			{
				return 0.0;
			}
			return 100.0 * (currentWealth - baselineWealth) / (double) baselineWealth;
		}
	}

	/**
	 * Compares one account against its own history.
	 *
	 * @param history may be {@code null} for an account with no snapshots yet
	 */
	static Change forAccount(AccountRecord record, AccountHistory history, Timeframe timeframe, long now)
	{
		Change change = new Change();
		change.currentWealth = record.totalWealth();
		change.currentXp = record.totalXp();
		change.currentTotalLevel = record.totalLevel();

		if (history == null || history.isEmpty())
		{
			// No history: baseline is the present, so every delta is zero.
			change.baselineWealth = change.currentWealth;
			change.baselineXp = change.currentXp;
			change.baselineTotalLevel = change.currentTotalLevel;
			return change;
		}

		HistorySnapshot baseline = history.baselineFor(timeframe, now);
		boolean covered = baseline != null;
		if (baseline == null)
		{
			// History exists but does not reach back far enough. Measure from
			// the oldest point available and mark the window as partial.
			baseline = history.earliest();
		}

		if (baseline == null)
		{
			change.baselineWealth = change.currentWealth;
			change.baselineXp = change.currentXp;
			change.baselineTotalLevel = change.currentTotalLevel;
			return change;
		}

		change.empty = false;
		change.covered = covered;
		change.baselineAt = baseline.at;
		change.baselineWealth = baseline.totalWealth();
		change.baselineXp = baseline.totalXp;
		change.baselineTotalLevel = baseline.totalLevel;
		return change;
	}

	/**
	 * Sums a whole roster into one comparison. Covered is {@code true} only if
	 * every contributing account reaches the window - one short history makes
	 * the roster total partial, and saying otherwise would overstate it.
	 */
	static Change forRoster(
		List<AccountRecord> accounts,
		Map<Long, AccountHistory> histories,
		Timeframe timeframe,
		long now)
	{
		Change total = new Change();
		total.covered = true;
		boolean anyBaseline = false;
		long earliestBaseline = 0L;

		for (AccountRecord record : accounts)
		{
			Change one = forAccount(record, histories.get(record.accountHash), timeframe, now);

			total.currentWealth += one.currentWealth;
			total.currentXp += one.currentXp;
			total.currentTotalLevel += one.currentTotalLevel;
			total.baselineWealth += one.baselineWealth;
			total.baselineXp += one.baselineXp;
			total.baselineTotalLevel += one.baselineTotalLevel;

			if (!one.empty)
			{
				anyBaseline = true;
				if (!one.covered)
				{
					total.covered = false;
				}
				if (earliestBaseline == 0L || one.baselineAt < earliestBaseline)
				{
					earliestBaseline = one.baselineAt;
				}
			}
		}

		total.empty = !anyBaseline;
		total.baselineAt = earliestBaseline;
		if (!anyBaseline)
		{
			total.covered = false;
		}
		return total;
	}

	/**
	 * Experience gained per skill for one account over the window. Skills with
	 * no gain are still present with a zero value, so the caller can render a
	 * full skill list without deciding what a missing key means.
	 */
	static Map<String, Long> skillXpDeltas(
		AccountRecord record, AccountHistory history, Timeframe timeframe, long now)
	{
		Map<String, Long> deltas = new HashMap<>();
		HistorySnapshot baseline = history == null
			? null
			: firstAvailableBaseline(history, timeframe, now);

		for (String skill : SkillOrder.names())
		{
			int current = record.skillXpFor(skill);
			int before = baseline == null ? current : baseline.skillXpFor(skill);
			// A baseline that predates a skill being observed reads as 0, which
			// would report the account's entire lifetime XP as a gain. Clamp to
			// zero rather than inventing progress that was not measured.
			long delta = before <= 0 ? 0L : current - before;
			deltas.put(skill, Math.max(0L, delta));
		}
		return deltas;
	}

	private static HistorySnapshot firstAvailableBaseline(
		AccountHistory history, Timeframe timeframe, long now)
	{
		HistorySnapshot baseline = history.baselineFor(timeframe, now);
		return baseline != null ? baseline : history.earliest();
	}

	/**
	 * Per-account wealth changes over the window, biggest gain first. Drives
	 * the timeframe-aware wealth split and the movers on the Interesting tab.
	 */
	static List<AccountChange> rankByWealthChange(
		List<AccountRecord> accounts,
		Map<Long, AccountHistory> histories,
		Timeframe timeframe,
		long now)
	{
		List<AccountChange> ranked = new ArrayList<>();
		for (AccountRecord record : accounts)
		{
			Change change = forAccount(record, histories.get(record.accountHash), timeframe, now);
			ranked.add(new AccountChange(record, change));
		}
		ranked.sort((a, b) -> Long.compare(b.change.wealthDelta(), a.change.wealthDelta()));
		return ranked;
	}

	/** One account paired with its change over a window. */
	static class AccountChange
	{
		final AccountRecord record;
		final Change change;

		AccountChange(AccountRecord record, Change change)
		{
			this.record = record;
			this.change = change;
		}
	}

	/** Convenience: account hash to history, for the roster-wide calls above. */
	static Map<Long, AccountHistory> index(List<AccountHistory> histories)
	{
		Map<Long, AccountHistory> byHash = new HashMap<>();
		for (AccountHistory history : histories)
		{
			byHash.put(history.accountHash, history);
		}
		return byHash;
	}
}
