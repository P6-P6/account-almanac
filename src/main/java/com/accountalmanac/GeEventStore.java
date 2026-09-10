package com.accountalmanac;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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

		int cap = Math.max(100, maxEvents);
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
		String json;
		synchronized (this)
		{
			if (!dirty)
			{
				return false;
			}
			json = serialiseLocked();
		}

		if (JsonFile.writeText(dataFile, json))
		{
			return true;
		}
		synchronized (this)
		{
			// Put it back so the next flush retries rather than dropping data.
			dirty = true;
		}
		return false;
	}

	/** Serialises the current state. Caller must hold the monitor. */
	private String serialiseLocked()
	{
		dirty = false;
		return gson.toJson(data);
	}

}
