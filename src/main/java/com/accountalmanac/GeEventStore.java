package com.accountalmanac;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Append-only log of Grand Exchange events, persisted to
 * {@code ge-events.json}.
 *
 * <p>This is what the GE log, purchase history and sale history all read from.
 * Events are kept oldest-first on disk and returned newest-first for display,
 * which is the order every view of a log actually wants.
 *
 * <p>The log is capped rather than unbounded. A busy flipper generates a lot
 * of offers, and an unbounded file would eventually make every save rewrite
 * megabytes on the executor thread.
 */
@Slf4j
@Singleton
class GeEventStore
{
	private static final String FILE_NAME = "ge-events.json";

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final File dataFile;

	private GeEventData data = new GeEventData();
	private volatile boolean dirty;

	/**
	 * The file's timestamp as of our last read or write. Anything newer is
	 * another client's trades, which are merged in before we write over them.
	 */
	private long diskStamp;

	/** The cap the last append used, so a merge trims to the same size. */
	private int cap = 100_000;

	/**
	 * Bumped on every change to the log.
	 *
	 * <p>Lets a reader tell "nothing has changed" apart from "I should re-read"
	 * without copying and sorting the whole list to find out. The viewer
	 * refreshes every five seconds while anything is dirty, and at a hundred
	 * thousand events that copy-and-sort on the Swing thread is what made the
	 * window stutter.
	 */
	private volatile long revision;

	@Inject
	GeEventStore(Gson gson, ScheduledExecutorService executor)
	{
		// Not pretty-printed, unlike the other two stores. This file is the one
		// that grows without bound, it is only ever read by code, and the
		// indentation costs about a third of its size - 100 MB of whitespace at
		// the upper end of the configurable range.
		this.gson = gson;
		this.executor = executor;
		this.dataFile = new File(new File(RuneLite.RUNELITE_DIR, "accountalmanac"), FILE_NAME);
	}

	void loadAsync(Runnable onLoaded)
	{
		executor.execute(() ->
		{
			synchronized (this)
			{
				data = JsonFile.read(dataFile, gson, GeEventData.class, GeEventData::new);
				data.normalise();
				diskStamp = dataFile.lastModified();
				revision++;
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
	 * Appends events and trims the log back to {@code maxEvents}, dropping the
	 * oldest first.
	 *
	 * @return {@code true} if anything was actually appended
	 */
	synchronized boolean append(Collection<GeEvent> events, int maxEvents)
	{
		if (events == null || events.isEmpty())
		{
			return false;
		}

		data.events.addAll(events);

		cap = Math.max(100, maxEvents);
		if (data.events.size() > cap)
		{
			// subList().clear() on an ArrayList removes the range in one
			// System.arraycopy rather than shifting per removal.
			data.events.subList(0, data.events.size() - cap).clear();
		}

		dirty = true;
		revision++;
		return true;
	}

	/** Current revision, for readers deciding whether to re-read. */
	long revision()
	{
		return revision;
	}

	/** Every event, newest first. Defensive copy, safe for the Swing thread. */
	synchronized List<GeEvent> getEventsNewestFirst()
	{
		List<GeEvent> copy = new ArrayList<>(data.events);
		copy.sort(Comparator.comparingLong((GeEvent e) -> e.at).reversed());
		return copy;
	}

	synchronized int eventCount()
	{
		return data.events.size();
	}

	/** Oldest event timestamp, or 0 when the log is empty. */
	synchronized long earliestEventAt()
	{
		long earliest = 0L;
		for (GeEvent event : data.events)
		{
			if (earliest == 0L || event.at < earliest)
			{
				earliest = event.at;
			}
		}
		return earliest;
	}

	synchronized void removeAccount(long accountHash)
	{
		if (data.events.removeIf(e -> e.accountHash == accountHash))
		{
			dirty = true;
			revision++;
			saveAsync();
		}
	}

	private void saveAsync()
	{
		executor.execute(this::flushIfDirty);
	}

	/**
	 * @return {@code true} if there were unsaved events and they were written
	 */
	boolean flushIfDirty()
	{
		// Locked for the whole read-merge-write, so no other client can write
		// between this client reading the file and replacing it.
		return JsonFile.locked(dataFile, this::flushLocked);
	}

	private boolean flushLocked()
	{
		// This write replaces the whole log, so when there is something to write
		// the file is read first regardless of its timestamp: two clients can
		// write inside the same millisecond. Otherwise the timestamp decides
		// whether anything is worth reading.
		boolean writing;
		synchronized (this)
		{
			writing = dirty;
		}
		GeEventData external = writing ? readFromDisk() : readIfChangedExternally();

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

	private GeEventData readFromDisk()
	{
		GeEventData loaded = JsonFile.read(dataFile, gson, GeEventData.class, GeEventData::new);
		loaded.normalise();
		synchronized (this)
		{
			diskStamp = dataFile.lastModified();
		}
		return loaded;
	}

	/** The file as another client left it, or {@code null} when we wrote it last. */
	private GeEventData readIfChangedExternally()
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
	 * Folds another client's log into this one.
	 *
	 * <p>The log only ever gains entries, so the two sides are unioned rather
	 * than one replacing the other - each client records the account it has
	 * open. An event is the same event when every field a trade is described by
	 * matches, which is what stops a merge duplicating what both sides already
	 * hold.
	 *
	 * @return {@code true} if anything was taken on
	 */
	private boolean mergeLocked(GeEventData disk)
	{
		Set<String> taken = new HashSet<>();
		for (GeEvent event : data.events)
		{
			taken.add(identity(event));
		}

		boolean changed = false;
		for (GeEvent event : disk.events)
		{
			if (taken.add(identity(event)))
			{
				data.events.add(event);
				changed = true;
			}
		}

		if (!changed)
		{
			return false;
		}

		data.events.sort(Comparator.comparingLong(event -> event.at));
		if (data.events.size() > cap)
		{
			data.events.subList(0, data.events.size() - cap).clear();
		}
		revision++;
		return true;
	}

	/** Everything that distinguishes one logged trade from another. */
	private static String identity(GeEvent event)
	{
		return event.at + "|" + event.accountHash + "|" + event.type + "|" + event.slot
			+ "|" + event.itemId + "|" + event.quantity + "|" + event.pricePerItem
			+ "|" + event.totalValue;
	}

	/** Serialises the current state. Caller must hold the monitor. */
	private String serialiseLocked()
	{
		dirty = false;
		return gson.toJson(data);
	}

}
