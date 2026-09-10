package com.accountalmanac;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One account's snapshots over time, oldest first.
 *
 * <p>Snapshots are appended at a throttled cadence rather than on every
 * change, and older ones are thinned as they age. Without thinning, a year of
 * daily snapshots across forty accounts would be roughly fifteen thousand
 * records each carrying a skill map, which is a lot of JSON to rewrite every
 * time one account gains experience.
 */
class AccountHistory
{
	/** Retain every snapshot newer than this at full daily resolution. */
	private static final long FULL_DETAIL_DAYS = 30L;

	/** Between full detail and this age, thin to roughly one per week. */
	private static final long WEEKLY_DETAIL_DAYS = 180L;

	private static final long ONE_DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
	private static final long ONE_WEEK_MILLIS = TimeUnit.DAYS.toMillis(7);
	private static final long ONE_MONTH_MILLIS = TimeUnit.DAYS.toMillis(30);

	long accountHash;

	List<HistorySnapshot> snapshots = new ArrayList<>();

	AccountHistory()
	{
	}

	AccountHistory(long accountHash)
	{
		this.accountHash = accountHash;
	}

	void normalise()
	{
		if (snapshots == null)
		{
			snapshots = new ArrayList<>();
			return;
		}
		for (HistorySnapshot snapshot : snapshots)
		{
			snapshot.normalise();
		}
		snapshots.sort(Comparator.comparingLong(s -> s.at));
	}

	HistorySnapshot latest()
	{
		return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
	}

	HistorySnapshot earliest()
	{
		return snapshots.isEmpty() ? null : snapshots.get(0);
	}

	boolean isEmpty()
	{
		return snapshots.isEmpty();
	}

	/**
	 * The most recent snapshot taken at or before {@code cutoff} - the baseline
	 * a timeframe comparison subtracts from the present.
	 *
	 * <p>Returns {@code null} when history does not reach back that far, which
	 * callers must surface rather than silently substituting the oldest
	 * snapshot: reporting a year of gains from a week of data would be wrong,
	 * and quietly so. {@link #baselineFor} is the forgiving variant, for
	 * callers that explicitly want "as far back as we go".
	 */
	HistorySnapshot atOrBefore(long cutoff)
	{
		HistorySnapshot best = null;
		for (HistorySnapshot snapshot : snapshots)
		{
			if (snapshot.at <= cutoff)
			{
				best = snapshot;
			}
			else
			{
				// Sorted ascending, so nothing further can qualify.
				break;
			}
		}
		return best;
	}

	/**
	 * Baseline for a timeframe. For {@link Timeframe#ALL_TIME} this is the
	 * earliest snapshot; otherwise the newest one at or before the cutoff, or
	 * {@code null} if history is too short to cover the window.
	 */
	HistorySnapshot baselineFor(Timeframe timeframe, long now)
	{
		if (timeframe.isAllTime())
		{
			return earliest();
		}
		return atOrBefore(timeframe.cutoffFrom(now));
	}

	/**
	 * Appends a snapshot if enough time has passed since the last one.
	 *
	 * <p>The most recent snapshot is <em>replaced</em> rather than kept when
	 * inside the interval, so the newest figures are always current while the
	 * record count stays bounded. The very first snapshot for an account is
	 * always accepted, since it is the baseline everything else measures from.
	 *
	 * @param minIntervalMillis smallest gap between retained snapshots
	 * @return {@code true} if the stored history actually changed
	 */
	boolean record(HistorySnapshot snapshot, long minIntervalMillis)
	{
		if (snapshot == null || snapshot.isEmpty())
		{
			return false;
		}

		// Replaced wholesale rather than mutated, for the same reason the skill
		// maps are: this runs on the executor thread while the Swing thread
		// iterates the list to render the Wealth history tab.
		HistorySnapshot last = latest();
		if (last == null)
		{
			List<HistorySnapshot> updated = new ArrayList<>(snapshots);
			updated.add(snapshot);
			snapshots = updated;
			return true;
		}

		if (snapshot.at - last.at >= minIntervalMillis)
		{
			List<HistorySnapshot> updated = new ArrayList<>(snapshots);
			updated.add(snapshot);
			snapshots = updated;
			prune(snapshot.at);
			return true;
		}

		// Inside the interval: refresh the tip so "now" is accurate, but only
		// when something actually moved, or an idle account would rewrite the
		// file on every flush.
		if (last.totalWealth() != snapshot.totalWealth()
			|| last.totalXp != snapshot.totalXp
			|| last.totalLevel != snapshot.totalLevel)
		{
			List<HistorySnapshot> updated = new ArrayList<>(snapshots);
			updated.set(updated.size() - 1, snapshot);
			snapshots = updated;
			return true;
		}

		return false;
	}

	/**
	 * Thins older snapshots: daily inside 30 days, weekly out to 180 days,
	 * monthly beyond that. The oldest snapshot is always kept, because it is
	 * the all-time baseline.
	 */
	void prune(long now)
	{
		if (snapshots.size() < 3)
		{
			return;
		}

		List<HistorySnapshot> kept = new ArrayList<>(snapshots.size());
		HistorySnapshot previousKept = null;

		for (int i = 0; i < snapshots.size(); i++)
		{
			HistorySnapshot snapshot = snapshots.get(i);
			boolean isFirst = i == 0;
			boolean isLast = i == snapshots.size() - 1;

			if (isFirst || isLast)
			{
				kept.add(snapshot);
				previousKept = snapshot;
				continue;
			}

			long ageDays = TimeUnit.MILLISECONDS.toDays(Math.max(0L, now - snapshot.at));
			long requiredGap;
			if (ageDays <= FULL_DETAIL_DAYS)
			{
				requiredGap = ONE_DAY_MILLIS;
			}
			else if (ageDays <= WEEKLY_DETAIL_DAYS)
			{
				requiredGap = ONE_WEEK_MILLIS;
			}
			else
			{
				requiredGap = ONE_MONTH_MILLIS;
			}

			if (previousKept == null || snapshot.at - previousKept.at >= requiredGap)
			{
				kept.add(snapshot);
				previousKept = snapshot;
			}
		}

		snapshots = kept;
	}
}
