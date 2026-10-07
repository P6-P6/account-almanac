package com.accountalmanac;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Persists per-account snapshot history to {@code history.json}.
 *
 * <p>Follows the same discipline as {@link AccountStore}: all mutation is
 * synchronized, disk IO happens on the injected executor rather than the
 * client thread, and high-frequency updates only mark dirty so a periodic
 * flush picks them up.
 *
 * <p>Snapshots are throttled by {@code minIntervalHours} - the default of one
 * per day gives the 7 day window a week of distinct points to compare, while
 * keeping a year of history for forty accounts to a few thousand records
 * rather than hundreds of thousands.
 */
@Slf4j
@Singleton
class HistoryStore
{
	private static final String FILE_NAME = "history.json";

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final File dataFile;

	private HistoryData data = new HistoryData();
	private volatile boolean dirty;

	/**
	 * The file's timestamp as of our last read or write. Anything newer is
	 * another client's work, which is merged in before we write over it.
	 */
	private long diskStamp;

	@Inject
	HistoryStore(Gson gson, ScheduledExecutorService executor)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.executor = executor;
		this.dataFile = new File(new File(RuneLite.RUNELITE_DIR, "accountalmanac"), FILE_NAME);
	}

	void loadAsync(Runnable onLoaded)
	{
		executor.execute(() ->
		{
			synchronized (this)
			{
				data = JsonFile.read(dataFile, gson, HistoryData.class, HistoryData::new);
				data.normalise();
				diskStamp = dataFile.lastModified();
			}
			if (onLoaded != null)
			{
				onLoaded.run();
			}
		});
	}

	File file()
	{
		return dataFile;
	}

	/**
	 * Records the account's current figures as a snapshot, subject to the
	 * throttle interval.
	 *
	 * @param minIntervalHours smallest gap between retained snapshots
	 * @return {@code true} if history changed and a flush is warranted
	 */
	synchronized boolean recordSnapshot(AccountRecord record, long now, int minIntervalHours)
	{
		if (record == null)
		{
			return false;
		}

		HistorySnapshot snapshot = HistorySnapshot.of(record, now);
		if (snapshot.isEmpty())
		{
			// Nothing observed for this account yet - an empty baseline would
			// make the first real snapshot look like an enormous gain.
			return false;
		}

		long interval = TimeUnit.HOURS.toMillis(Math.max(1, minIntervalHours));
		AccountHistory history = findOrCreate(record.accountHash);
		if (history.record(snapshot, interval))
		{
			dirty = true;
			return true;
		}
		return false;
	}

	synchronized AccountHistory historyFor(long accountHash)
	{
		for (AccountHistory history : data.histories)
		{
			if (history.accountHash == accountHash)
			{
				return history;
			}
		}
		return null;
	}

	/** Defensive copy, safe to read from the Swing thread. */
	synchronized List<AccountHistory> getHistories()
	{
		return new ArrayList<>(data.histories);
	}

	synchronized int totalSnapshotCount()
	{
		int count = 0;
		for (AccountHistory history : data.histories)
		{
			count += history.snapshots.size();
		}
		return count;
	}

	/**
	 * Oldest snapshot timestamp across every account, or 0 if there is none.
	 * Used to tell the user how far their history actually reaches, so a
	 * "past year" window can say when it is really showing less.
	 */
	synchronized long earliestSnapshotAt()
	{
		long earliest = 0L;
		for (AccountHistory history : data.histories)
		{
			HistorySnapshot first = history.earliest();
			if (first != null && (earliest == 0L || first.at < earliest))
			{
				earliest = first.at;
			}
		}
		return earliest;
	}

	/**
	 * Updates the price baselines from a fresh set of prices.
	 *
	 * <p>An item with no baseline yet records one and reports no movement -
	 * "up 100%" on the first sighting would be nonsense. An existing baseline
	 * older than {@code windowMillis} rolls forward to the current price, so
	 * the comparison stays roughly one window wide instead of drifting back to
	 * whenever the plugin first ran.
	 *
	 * @return {@code true} if anything changed and a flush is warranted
	 */
	synchronized boolean updatePriceBaselines(Map<Integer, Integer> current, long now, long windowMillis)
	{
		boolean changed = false;
		for (Map.Entry<Integer, Integer> entry : current.entrySet())
		{
			if (entry.getValue() == null || entry.getValue() <= 0)
			{
				continue;
			}
			String key = Integer.toString(entry.getKey());
			PricePoint existing = data.priceBaselines.get(key);
			if (existing == null || now - existing.at >= windowMillis)
			{
				data.priceBaselines.put(key, new PricePoint(entry.getValue(), now));
				changed = true;
			}
		}
		if (changed)
		{
			dirty = true;
		}
		return changed;
	}

	/** The baseline for an item, or {@code null} if it has never been priced. */
	synchronized PricePoint priceBaseline(int itemId)
	{
		return data.priceBaselines.get(Integer.toString(itemId));
	}

	synchronized void removeAccount(long accountHash)
	{
		if (data.histories.removeIf(h -> h.accountHash == accountHash))
		{
			dirty = true;
			saveAsync();
		}
	}

	private void saveAsync()
	{
		executor.execute(this::flushIfDirty);
	}

	/**
	 * @return {@code true} if there was unsaved history and it was written
	 */
	boolean flushIfDirty()
	{
		// Locked for the whole read-merge-write, so no other client can write
		// between this client reading the file and replacing it.
		return JsonFile.locked(dataFile, this::flushLocked);
	}

	private boolean flushLocked()
	{
		// This write replaces the whole file, so when there is something to
		// write the file is read first regardless of its timestamp: two clients
		// can write inside the same millisecond. Otherwise the timestamp
		// decides whether anything is worth reading.
		boolean writing;
		synchronized (this)
		{
			writing = dirty;
		}
		HistoryData external = writing ? readFromDisk() : readIfChangedExternally();

		String json;
		synchronized (this)
		{
			boolean adopted = external != null && mergeLocked(external);
			if (!dirty)
			{
				return adopted;
			}
			json = serialiseLocked();
		}

		if (JsonFile.writeText(dataFile, json))
		{
			synchronized (this)
			{
				diskStamp = dataFile.lastModified();
			}
			return true;
		}
		synchronized (this)
		{
			// Put it back so the next flush retries rather than dropping data.
			dirty = true;
		}
		return false;
	}

	private HistoryData readFromDisk()
	{
		HistoryData loaded = JsonFile.read(dataFile, gson, HistoryData.class, HistoryData::new);
		loaded.normalise();
		synchronized (this)
		{
			diskStamp = dataFile.lastModified();
		}
		return loaded;
	}

	/** The file as another client left it, or {@code null} when we wrote it last. */
	private HistoryData readIfChangedExternally()
	{
		long modified = dataFile.lastModified();
		synchronized (this)
		{
			if (modified <= 0L || modified <= diskStamp)
			{
				return null;
			}
			diskStamp = modified;
		}
		return readFromDisk();
	}

	/**
	 * Folds another client's history into this one.
	 *
	 * <p>History only ever gains points, so the two sides are unioned by
	 * timestamp rather than one winning: each client records the accounts it
	 * has open, and the file has to end up with all of them. Baselines are the
	 * exception, where the more recent reading wins.
	 *
	 * @return {@code true} if anything was taken on
	 */
	private boolean mergeLocked(HistoryData disk)
	{
		boolean changed = false;

		Map<Long, AccountHistory> mine = new HashMap<>();
		for (AccountHistory history : data.histories)
		{
			mine.put(history.accountHash, history);
		}

		for (AccountHistory theirs : disk.histories)
		{
			AccountHistory ours = mine.get(theirs.accountHash);
			if (ours == null)
			{
				data.histories.add(theirs);
				changed = true;
				continue;
			}

			Set<Long> taken = new HashSet<>();
			for (HistorySnapshot snapshot : ours.snapshots)
			{
				taken.add(snapshot.at);
			}

			boolean added = false;
			for (HistorySnapshot snapshot : theirs.snapshots)
			{
				if (taken.add(snapshot.at))
				{
					ours.snapshots.add(snapshot);
					added = true;
				}
			}
			if (added)
			{
				ours.snapshots.sort(Comparator.comparingLong(snapshot -> snapshot.at));
				changed = true;
			}
		}

		for (Map.Entry<String, PricePoint> entry : disk.priceBaselines.entrySet())
		{
			PricePoint ours = data.priceBaselines.get(entry.getKey());
			if (ours == null || entry.getValue().at > ours.at)
			{
				data.priceBaselines.put(entry.getKey(), entry.getValue());
				changed = true;
			}
		}

		return changed;
	}

	/** Serialises the current state. Caller must hold the monitor. */
	private String serialiseLocked()
	{
		dirty = false;
		return gson.toJson(data);
	}


	private AccountHistory findOrCreate(long accountHash)
	{
		AccountHistory existing = historyFor(accountHash);
		if (existing != null)
		{
			return existing;
		}
		AccountHistory created = new AccountHistory(accountHash);
		data.histories.add(created);
		return created;
	}
}
