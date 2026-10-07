package com.accountalmanac;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

	/**
	 * How long skill-only changes may wait before being saved. See
	 * {@link #markSkillsDirty()}; also the cadence the plugin refreshes the
	 * logged-in account's history point at while only XP is moving.
	 */
	static final long SKILL_SAVE_INTERVAL_MILLIS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final File dataFile;

	private TrackerData data = new TrackerData();
	private volatile boolean dirty;

	/** Skill changes not yet saved. Guarded by this. */
	private boolean skillsDirty;

	/** When the file was last written or read. Guarded by this. */
	private long lastWrittenAt;

	/**
	 * The file's own timestamp as of our last read or write.
	 *
	 * <p>Anything newer means another client has written since, and its copy is
	 * merged in before we write over the file. Every save rewrites the whole
	 * roster, so without this the last client to save erases whatever the
	 * others recorded while it was open.
	 */
	private long diskStamp;

	/** How long a deletion is remembered for, so a merge cannot undo it. */
	private static final long TOMBSTONE_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(30);

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
				// Memory now matches the file, so the skill-save interval
				// counts from here rather than from the epoch.
				lastWrittenAt = System.currentTimeMillis();
				diskStamp = dataFile.lastModified();
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
	 * Marks in-memory data as needing a save without writing immediately, for
	 * changes that arrive in bursts - Grand Exchange slots after login, prices
	 * after a reprice - so the periodic {@link #flushIfDue} picks them up.
	 */
	private void markDirty()
	{
		dirty = true;
	}

	/**
	 * Marks skill data as changed. Kept apart from {@link #markDirty()}
	 * because it fires on every XP drop: this file holds every account's bank,
	 * so saving whenever a skill moved rewrote megabytes every few seconds
	 * while training. Skill changes are saved at most every
	 * {@link #SKILL_SAVE_INTERVAL_MILLIS}, or sooner whenever anything else is
	 * written. Nothing is lost for long, and the game re-reports every skill at
	 * login regardless.
	 */
	private void markSkillsDirty()
	{
		skillsDirty = true;
	}

	/**
	 * Writes everything outstanding, skill changes included - for shutdown,
	 * logout, backups and immediate saves.
	 *
	 * @return {@code true} if there was unsaved data and it was flushed.
	 */
	boolean flushIfDirty()
	{
		return flush(true, System.currentTimeMillis());
	}

	/**
	 * The periodic save: other changes are written straight away, skill-only
	 * changes once {@link #SKILL_SAVE_INTERVAL_MILLIS} has passed since the
	 * file was last written.
	 *
	 * @return {@code true} if data was flushed.
	 */
	boolean flushIfDue(long now)
	{
		return flush(false, now);
	}

	private boolean flush(boolean includeSkills, long now)
	{
		// Locked for the whole read-merge-write, so no other client can write
		// between this client reading the file and replacing it.
		return JsonFile.locked(dataFile, () -> flushLocked(includeSkills, now));
	}

	private boolean flushLocked(boolean includeSkills, long now)
	{
		boolean writing;
		synchronized (this)
		{
			writing = dirty || (skillsDirty
				&& (includeSkills || now - lastWrittenAt >= SKILL_SAVE_INTERVAL_MILLIS));
		}

		// About to replace the whole file, so read it first no matter what the
		// timestamp says: two clients can write inside the same millisecond,
		// and the loser of that race would otherwise be erased. When there is
		// nothing of ours to write, the timestamp is enough to decide.
		//
		// Read outside the monitor either way: parsing a large roster takes
		// long enough that holding the lock would stall the client thread.
		TrackerData external = writing ? readFromDisk() : readIfChangedExternally();

		String json;
		synchronized (this)
		{
			boolean adopted = external != null && mergeLocked(external, now);
			boolean skillsDue = skillsDirty
				&& (includeSkills || now - lastWrittenAt >= SKILL_SAVE_INTERVAL_MILLIS);
			if (!dirty && !skillsDue)
			{
				// Nothing of ours to write, but another client's work was taken
				// on, so the caller still repaints.
				return adopted;
			}
			// Serialised while holding the monitor so the snapshot is
			// consistent, but the disk write happens after releasing it: the
			// client thread contends on this same lock through updateSkill,
			// updateBank and updateGeOffer, and holding it across the write
			// would block the game on IO.
			json = gson.toJson(data);
			dirty = false;
			skillsDirty = false;
		}

		if (JsonFile.writeText(dataFile, json))
		{
			synchronized (this)
			{
				lastWrittenAt = now;
				diskStamp = dataFile.lastModified();
			}
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
	 * state and marks skills changed - see {@link #markSkillsDirty()}.
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
		markSkillsDirty();
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

	/**
	 * Records the account's quest points and the game's current maximum.
	 *
	 * <p>A maximum of zero is ignored, the same way playtime ignores zero:
	 * the varbit is unpopulated for the first few ticks after login, and a
	 * zero denominator would render as an obviously wrong "0/0". The score
	 * itself is trusted at zero, because a fresh account really does have
	 * none.
	 */
	synchronized void updateQuestPoints(long accountHash, int points, int max)
	{
		if (max <= 0 || points < 0)
		{
			return;
		}
		AccountRecord record = findOrCreate(accountHash);
		if (record.questPoints != points || record.questPointsMax != max)
		{
			record.questPoints = points;
			record.questPointsMax = max;
			markDirty();
		}
	}

	/**
	 * Quests completed and collection log slots filled, both read from vars.
	 *
	 * <p>A zero total is ignored the same way quest points ignore one: the vars
	 * are unpopulated for the first few ticks after login, and writing that
	 * would replace a real figure with "0/0".
	 */
	synchronized void updateSummaryCounts(long accountHash, int questsDone, int questsTotal,
		int collectionsDone, int collectionsTotal)
	{
		AccountRecord record = findOrCreate(accountHash);
		boolean changed = false;

		if (questsTotal > 0 && questsDone >= 0
			&& (record.questsCompleted != questsDone || record.questsTotal != questsTotal))
		{
			record.questsCompleted = questsDone;
			record.questsTotal = questsTotal;
			changed = true;
		}
		if (collectionsTotal > 0 && collectionsDone >= 0
			&& (record.collectionsLogged != collectionsDone || record.collectionsTotal != collectionsTotal))
		{
			record.collectionsLogged = collectionsDone;
			record.collectionsTotal = collectionsTotal;
			changed = true;
		}

		if (changed)
		{
			markDirty();
		}
	}

	/**
	 * Achievement diary tasks and combat tasks, as read off the Account Summary
	 * screen. Either may be absent from a given read, so a zero total leaves
	 * whatever was captured before alone.
	 */
	synchronized void updateSummaryTasks(long accountHash, int achievementsDone, int achievementsTotal,
		int combatDone, int combatTotal)
	{
		AccountRecord record = findOrCreate(accountHash);
		boolean changed = false;

		if (achievementsTotal > 0 && achievementsDone >= 0
			&& (record.achievementsCompleted != achievementsDone || record.achievementsTotal != achievementsTotal))
		{
			record.achievementsCompleted = achievementsDone;
			record.achievementsTotal = achievementsTotal;
			changed = true;
		}
		if (combatTotal > 0 && combatDone >= 0
			&& (record.combatTasksCompleted != combatDone || record.combatTasksTotal != combatTotal))
		{
			record.combatTasksCompleted = combatDone;
			record.combatTasksTotal = combatTotal;
			changed = true;
		}

		if (changed)
		{
			markDirty();
		}
	}

	synchronized void updateCategory(long accountHash, String category)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.category = AccountCategory.normalise(category);
		record.editedAt = System.currentTimeMillis();
		saveAsync();
	}

	synchronized void updateHidden(long accountHash, boolean hidden)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.hidden = hidden;
		record.editedAt = System.currentTimeMillis();
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
		record.editedAt = System.currentTimeMillis();
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
		record.editedAt = System.currentTimeMillis();
		saveAsync();
	}

	synchronized void updateLoginLabel(long accountHash, String loginLabel)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.loginLabel = loginLabel == null ? "" : loginLabel;
		record.editedAt = System.currentTimeMillis();
		saveAsync();
	}

	synchronized void updateNote(long accountHash, String note)
	{
		AccountRecord record = findOrCreate(accountHash);
		record.note = note == null ? "" : note;
		record.editedAt = System.currentTimeMillis();
		saveAsync();
	}

	synchronized void removeAccount(long accountHash)
	{
		data.accounts.removeIf(r -> r.accountHash == accountHash);
		// Remembered, or a second client still holding the account writes it
		// straight back on its next save.
		data.removedAccounts.put(Long.toString(accountHash), System.currentTimeMillis());
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
	/**
	 * Fills in the members flag for stored items that predate it being
	 * recorded.
	 *
	 * <p>Unlike the alch backfill this overwrites a known value rather than
	 * only filling blanks: whether an item is members-only is a property of
	 * the item, and if the game changes it the newer answer is the right one.
	 */
	synchronized boolean applyMembersFlags(Map<Integer, Boolean> membersById)
	{
		boolean changed = false;
		for (AccountRecord record : data.accounts)
		{
			for (BankItem item : record.bankItems)
			{
				Boolean members = membersById.get(item.id);
				if (members != null && !members.equals(item.members))
				{
					item.members = members;
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

	/** The file as another client left it, or {@code null} when we wrote it last. */
	private TrackerData readIfChangedExternally()
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
	 * Folds another client's copy of the roster into this one.
	 *
	 * <p>Two clients are never logged into the same account, so for any account
	 * one side holds a live session and the other a copy taken before that
	 * client started. Each part of a record is settled by its own timestamp -
	 * the bank by when it was last seen, identity and skills by the last login,
	 * offers by when the slots were reported - and the newer side wins. Fields
	 * the user edits by hand go by {@link AccountRecord#editedAt} instead.
	 *
	 * @return {@code true} if anything was taken on
	 */
	private boolean mergeLocked(TrackerData disk, long now)
	{
		boolean changed = false;

		for (Map.Entry<String, Long> entry : disk.removedAccounts.entrySet())
		{
			Long ours = data.removedAccounts.get(entry.getKey());
			if (ours == null || entry.getValue() > ours)
			{
				data.removedAccounts.put(entry.getKey(), entry.getValue());
				changed = true;
			}
		}
		data.removedAccounts.values().removeIf(at -> now - at > TOMBSTONE_MILLIS);

		Map<Long, AccountRecord> mine = new HashMap<>();
		for (AccountRecord record : data.accounts)
		{
			mine.put(record.accountHash, record);
		}

		for (AccountRecord theirs : disk.accounts)
		{
			AccountRecord ours = mine.get(theirs.accountHash);
			if (ours == null)
			{
				if (deletedAfter(theirs))
				{
					continue;
				}
				data.accounts.add(theirs);
				changed = true;
				continue;
			}
			changed |= mergeRecord(ours, theirs);
		}

		// A deletion recorded by either side applies to both.
		changed |= data.accounts.removeIf(this::deletedAfter);
		return changed;
	}

	/** Whether this account was deleted after the last thing it recorded. */
	private boolean deletedAfter(AccountRecord record)
	{
		Long removedAt = data.removedAccounts.get(Long.toString(record.accountHash));
		return removedAt != null && removedAt >= Math.max(record.lastActivityAt(), record.editedAt);
	}

	private static boolean mergeRecord(AccountRecord ours, AccountRecord theirs)
	{
		boolean changed = false;

		if (theirs.lastLoginAt > ours.lastLoginAt)
		{
			ours.lastLoginAt = theirs.lastLoginAt;
			ours.displayName = theirs.displayName;
			ours.loginName = theirs.loginName;
			ours.accountType = theirs.accountType;
			ours.playtimeMinutes = theirs.playtimeMinutes;
			ours.questPoints = theirs.questPoints;
			ours.questPointsMax = theirs.questPointsMax;
			ours.questsCompleted = theirs.questsCompleted;
			ours.questsTotal = theirs.questsTotal;
			ours.achievementsCompleted = theirs.achievementsCompleted;
			ours.achievementsTotal = theirs.achievementsTotal;
			ours.combatTasksCompleted = theirs.combatTasksCompleted;
			ours.combatTasksTotal = theirs.combatTasksTotal;
			ours.collectionsLogged = theirs.collectionsLogged;
			ours.collectionsTotal = theirs.collectionsTotal;
			ours.skillLevels = theirs.skillLevels;
			ours.skillXp = theirs.skillXp;
			ours.combatLevel = theirs.combatLevel;
			changed = true;
		}
		else if (theirs.lastLoginAt == ours.lastLoginAt && theirs.totalXp() > ours.totalXp())
		{
			// The same session seen by both: more experience means seen later.
			ours.skillLevels = theirs.skillLevels;
			ours.skillXp = theirs.skillXp;
			ours.combatLevel = theirs.combatLevel;
			changed = true;
		}

		if (theirs.lastSnapshotAt > ours.lastSnapshotAt)
		{
			ours.bankItems = theirs.bankItems;
			ours.bankValue = theirs.bankValue;
			ours.lastUpdated = theirs.lastUpdated;
			ours.lastSnapshotAt = theirs.lastSnapshotAt;
			changed = true;
		}

		if (newestOfferAt(theirs) > newestOfferAt(ours))
		{
			ours.geOffers = theirs.geOffers;
			changed = true;
		}

		// Last, so an explicit edit beats a name captured on login.
		if (theirs.editedAt > ours.editedAt)
		{
			ours.editedAt = theirs.editedAt;
			ours.displayName = theirs.displayName;
			ours.loginLabel = theirs.loginLabel;
			ours.note = theirs.note;
			ours.category = theirs.category;
			ours.hidden = theirs.hidden;
			ours.banned = theirs.banned;
			ours.bannedAt = theirs.bannedAt;
			changed = true;
		}

		return changed;
	}

	/** When this account's Grand Exchange slots were last reported. */
	private static long newestOfferAt(AccountRecord record)
	{
		long newest = 0L;
		for (GrandExchangeRecord offer : record.geOffers)
		{
			newest = Math.max(newest, offer.updatedAt);
		}
		return newest;
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
