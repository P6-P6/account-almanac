package com.accountalmanac;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Loads and saves the tracked-account file. Disk IO always happens off the
 * client thread via the injected executor; mutations to {@link #data} are
 * synchronized so a background save never races a client-thread update.
 *
 * <p>Collections on a record are always replaced wholesale rather than
 * mutated in place, so the Swing thread reading a record concurrently sees
 * either the old list or the new one - never a half-written one.
 *
 * <p>Renamed from {@code VaultStore} when the credential vault was removed.
 * The on-disk path is deliberately unchanged so existing data keeps loading.
 */
@Slf4j
@Singleton
class AccountStore
{
	private static final String FILE_NAME = "accounts.json";

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final File dataFile;

	private TrackerData data = new TrackerData();
	private volatile boolean dirty;

	@Inject
	AccountStore(Gson gson, ScheduledExecutorService executor)
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
				data = readFromDisk();
			}
			if (onLoaded != null)
			{
				onLoaded.run();
			}
		});
	}

	private TrackerData readFromDisk()
	{
		if (!dataFile.exists())
		{
			return new TrackerData();
		}
		try (Reader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8))
		{
			TrackerData loaded = gson.fromJson(reader, TrackerData.class);
			if (loaded == null)
			{
				return new TrackerData();
			}
			// Files written before the bank-items and GE fields existed
			// leave those collections null rather than empty.
			loaded.normalise();
			return loaded;
		}
		catch (JsonParseException | IOException e)
		{
			log.warn("Failed to read account tracker data; starting empty", e);
			return new TrackerData();
		}
	}

	/**
	 * Marks dirty and queues a flush. Callers are on the client thread, so the
	 * write itself must not happen here.
	 */
	private void saveAsync()
	{
		dirty = true;
		executor.execute(this::flushIfDirty);
	}

	/**
	 * Marks in-memory data as needing a save without writing immediately.
	 * Used for high-frequency updates - stat changes fire on every XP drop -
	 * so a periodic {@link #flushIfDirty()} picks them up instead.
	 */
	private void markDirty()
	{
		dirty = true;
	}

	/**
	 * @return {@code true} if there was unsaved data and it was flushed.
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
			// Serialised while holding the monitor so the snapshot is
			// consistent, but the disk write happens after releasing it: the
			// client thread contends on this same lock through updateSkill,
			// updateBank and updateGeOffer, and holding it across the write
			// would block the game on IO.
			json = gson.toJson(data);
			dirty = false;
		}

		if (JsonFile.writeText(dataFile, json))
		{
			return true;
		}

		synchronized (this)
		{
			// Cleared only on success. Clearing permanently meant a failed
			// write - a full disk, or the file briefly locked by a sync tool -
			// silently discarded everything accumulated since the last flush.
			dirty = true;
		}
		return false;
	}


	synchronized List<AccountRecord> getAccounts()
	{
		return new ArrayList<>(data.accounts);
	}

	/**
	 * Records the automatically-captured identity fields on login. A blank
	 * {@code loginName} or {@code accountType} is ignored rather than
	 * written, so a client that does not expose one never clears a value
	 * captured earlier.
	 */
	synchronized void touchAccount(long accountHash, String displayName, String loginName, String accountType)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.displayName = displayName;
		record.lastLoginAt = System.currentTimeMillis();
		if (loginName != null && !loginName.isEmpty())
		{
			record.loginName = loginName;
		}
		if (accountType != null && !accountType.isEmpty())
		{
			record.accountType = accountType;
		}
		saveAsync();
	}

	/**
	 * Replaces an account's bank snapshot. Items must already be
	 * canonicalised and priced by the caller on the client thread.
	 */
	synchronized void updateBank(long accountHash, List<BankItem> items, long bankValue)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.bankItems = new ArrayList<>(items);
		record.bankValue = bankValue;
		record.lastUpdated = System.currentTimeMillis();
		record.lastSnapshotAt = record.lastUpdated;
		saveAsync();
	}

	/**
	 * Fires on every XP drop while training, so this only updates in-memory
	 * state and marks dirty - see {@link #markDirty()}.
	 */
	synchronized void updateSkill(long accountHash, String skillName, int realLevel, int xp)
	{
		AccountRecord record = findOrCreate(accountHash);

		// Replaced wholesale rather than mutated in place, like every other
		// collection on a record. These two maps were the exception, and it was
		// a real bug: this runs on the client thread under this monitor, while
		// HistorySnapshot.of copies the same maps from the executor thread
		// under HistoryStore's monitor - different locks, so no mutual
		// exclusion. Training a skill during a flush threw
		// ConcurrentModificationException out of periodicFlush, which
		// ScheduledThreadPoolExecutor treats as fatal to the task: flushing and
		// UI refresh stopped for the rest of the session.
		//
		// Copying two ~23-entry maps per XP drop is nothing next to that.
		Map<String, Integer> levels = new HashMap<>(record.skillLevels);
		Map<String, Integer> experience = new HashMap<>(record.skillXp);
		levels.put(skillName, realLevel);
		experience.put(skillName, xp);

		record.skillLevels = levels;
		record.skillXp = experience;
		record.combatLevel = CombatLevel.calculate(levels);
		markDirty();
	}

	/**
	 * Records one GE slot. The client reports all eight slots shortly after
	 * login, so this is called in bursts rather than continuously; a slot is
	 * matched by index and replaced.
	 *
	 * <p>Returns the state the slot held beforehand, which is what
	 * {@link GeEventTracker} diffs against to decide whether an offer just
	 * started, finished or was collected. Swapping and returning in one
	 * synchronized step keeps that read-then-write atomic - fetching the
	 * previous value in a separate call would leave a window where two updates
	 * could interleave and produce a duplicated or missed event.
	 *
	 * @return the slot's previous contents, or {@code null} if this account has
	 *         never reported that slot before
	 */
	synchronized GrandExchangeRecord updateGeOffer(long accountHash, GrandExchangeRecord offer)
	{
		AccountRecord record = findOrCreate(accountHash);
		GrandExchangeRecord previous = null;
		for (GrandExchangeRecord existing : record.geOffers)
		{
			if (existing.slot == offer.slot)
			{
				previous = existing;
				break;
			}
		}

		List<GrandExchangeRecord> updated = new ArrayList<>(record.geOffers);
		updated.removeIf(existing -> existing.slot == offer.slot);
		updated.add(offer);
		updated.sort((a, b) -> Integer.compare(a.slot, b.slot));
		record.geOffers = updated;
		markDirty();
		return previous;
	}

	/**
	 * Records the account's reported playtime. Zero is ignored rather than
	 * written: the varp reads 0 before the account finishes loading, and
	 * storing that would wipe a good value captured earlier.
	 */
	synchronized void updatePlaytime(long accountHash, int minutes)
	{
		if (minutes <= 0)
		{
			return;
		}
		AccountRecord record = findOrCreate(accountHash);
		if (record.playtimeMinutes != minutes)
		{
			record.playtimeMinutes = minutes;
			markDirty();
		}
	}

	synchronized void updateCategory(long accountHash, String category)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.category = AccountCategory.normalise(category);
		saveAsync();
	}

	synchronized void updateHidden(long accountHash, boolean hidden)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.hidden = hidden;
		saveAsync();
	}

	/**
	 * Marks or clears the banned flag, stamping when it was set so the
	 * "recheck this" prompt has an age to work from. Clearing resets the
	 * stamp, so un-banning and re-banning starts the clock again.
	 */
	synchronized void updateBanned(long accountHash, boolean banned)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.banned = banned;
		record.bannedAt = banned ? System.currentTimeMillis() : 0L;
		saveAsync();
	}

	/** Looks up one account, or {@code null} if it is not tracked. */
	synchronized AccountRecord findAccount(long accountHash)
	{
		for (AccountRecord record : data.accounts)
		{
			if (record.accountHash == accountHash)
			{
				return record;
			}
		}
		return null;
	}

	/** Display label for an account hash, without exposing the record itself. */
	synchronized String labelFor(long accountHash)
	{
		AccountRecord record = findAccount(accountHash);
		return record == null ? ("Account " + Long.toHexString(accountHash)) : record.label();
	}

	File file()
	{
		return dataFile;
	}

	/**
	 * Overrides the display name captured on login.
	 *
	 * <p>An empty value clears the override, so the next login sets it from the
	 * client again rather than leaving the account permanently blank.
	 */
	synchronized void updateDisplayName(long accountHash, String displayName)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.displayName = displayName == null ? "" : displayName;
		saveAsync();
	}

	synchronized void updateLoginLabel(long accountHash, String loginLabel)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.loginLabel = loginLabel == null ? "" : loginLabel;
		saveAsync();
	}

	synchronized void updateNote(long accountHash, String note)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.note = note == null ? "" : note;
		saveAsync();
	}

	synchronized void removeAccount(long accountHash)
	{
		data.accounts.removeIf(r -> r.accountHash == accountHash);
		saveAsync();
	}

	/**
	 * Every distinct item id across every account's bank snapshot, so the
	 * caller can price them all in one pass on the client thread.
	 */
	synchronized Set<Integer> allBankItemIds()
	{
		Set<Integer> ids = new LinkedHashSet<>();
		for (AccountRecord record : data.accounts)
		{
			for (BankItem item : record.bankItems)
			{
				ids.add(item.id);
			}
			// Items sitting in a sell offer need a market price too - they are
			// counted as owned stock, and without a price they would be valued
			// at nothing.
			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (offer.isActive() && offer.itemId > 0)
				{
					ids.add(offer.itemId);
				}
			}
		}
		return ids;
	}

	/**
	 * Applies freshly-fetched unit prices to every stored snapshot and
	 * recomputes each account's bank value. Item ids missing from
	 * {@code pricesById} keep their previous price.
	 *
	 * @return {@code true} if any account's value actually changed.
	 */
	synchronized boolean applyPrices(Map<Integer, Integer> pricesById)
	{
		boolean changed = false;
		for (AccountRecord record : data.accounts)
		{
			List<BankItem> repriced = new ArrayList<>(record.bankItems.size());
			long total = 0L;
			boolean recordChanged = false;

			for (BankItem item : record.bankItems)
			{
				Integer fresh = pricesById.get(item.id);
				int price = fresh != null ? fresh : item.unitPrice;
				if (price != item.unitPrice)
				{
					recordChanged = true;
				}
				// haPrice carried through: alch value is fixed per item and is not
				// part of what a reprice refreshes.
				repriced.add(new BankItem(item.id, item.quantity, item.name, price, item.haPrice));
				total += (long) price * item.quantity;
			}

			// Offers carry their own market price, refreshed from the same feed.
			for (GrandExchangeRecord offer : record.geOffers)
			{
				Integer fresh = pricesById.get(offer.itemId);
				if (fresh != null && fresh != offer.marketPrice)
				{
					offer.marketPrice = fresh;
					recordChanged = true;
				}
			}

			if (recordChanged || total != record.bankValue)
			{
				record.bankItems = repriced;
				record.bankValue = total;
				changed = true;
			}
		}
		if (changed)
		{
			markDirty();
		}
		return changed;
	}

	/**
	 * Whether any stored item currently carries a non-zero price.
	 *
	 * <p>Used to tell a reprice that genuinely ran from one that found the
	 * price cache still empty and quietly did nothing.
	 */
	synchronized boolean hasPricedItems()
	{
		for (AccountRecord record : data.accounts)
		{
			for (BankItem item : record.bankItems)
			{
				if (item.unitPrice > 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Fills in high alchemy values on every stored item.
	 *
	 * <p>Separate from {@link #applyPrices} because these do not change: an
	 * item's alch value is fixed, so this is a backfill rather than a refresh,
	 * and an item that already has one is left alone.
	 *
	 * @return {@code true} if anything was filled in
	 */
	synchronized boolean applyAlchPrices(Map<Integer, Integer> alchById)
	{
		boolean changed = false;
		for (AccountRecord record : data.accounts)
		{
			for (BankItem item : record.bankItems)
			{
				if (item.haPrice > 0)
				{
					continue;
				}
				Integer ha = alchById.get(item.id);
				if (ha != null && ha > 0)
				{
					item.haPrice = ha;
					changed = true;
				}
			}
		}
		if (changed)
		{
			markDirty();
		}
		return changed;
	}

	/** Item id to display name, across every tracked account. */
	synchronized Map<Integer, String> knownItemNames()
	{
		Map<Integer, String> names = new HashMap<>();
		for (AccountRecord record : data.accounts)
		{
			for (BankItem item : record.bankItems)
			{
				if (item.name != null && !item.name.isEmpty())
				{
					names.putIfAbsent(item.id, item.name);
				}
			}
		}
		return names;
	}

	private AccountRecord findOrCreate(long accountHash)
	{
		for (AccountRecord record : data.accounts)
		{
			if (record.accountHash == accountHash)
			{
				return record;
			}
		}
		AccountRecord record = new AccountRecord();
		record.accountHash = accountHash;
		data.accounts.add(record);
		return record;
	}
}
