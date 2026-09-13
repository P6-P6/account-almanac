package com.accountalmanac;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Tracks every account logged into via this client - display name, bank
 * contents, Grand Exchange offers, skill levels and combat level - and
 * aggregates them into a cross-account wealth view.
 *
 * <p>Nothing here touches the network. All state lives under
 * {@code .runelite/accountalmanac/}, split across three files: the account
 * roster, snapshot history for timeframe comparisons, and the Grand Exchange
 * event log. See {@link AccountStore}, {@link HistoryStore} and
 * {@link GeEventStore}.
 *
 * <p>A bank can only be read while it is open, so each account's contents
 * are a snapshot from the last time its bank was opened in this client.
 * Grand Exchange offers do not have that limitation - the client reports
 * all eight slots shortly after login.
 */
@Slf4j
@PluginDescriptor(
	name = "Account Almanac",
	description = "Tracks bank contents, GE offers, stats and combat level across every account you play, with cross-account totals, history and charts",
	tags = {"alts", "accounts", "bank", "wealth", "stats", "combat", "grand", "exchange"}
)
public class AccountAlmanacPlugin extends Plugin
{
	/** How often dirty in-memory state is flushed to disk and the UI refreshed. */
	private static final int FLUSH_INTERVAL_SECONDS = 5;

	/** How often backups and pruning are considered. */
	private static final int MAINTENANCE_INTERVAL_MINUTES = 60;

	/**
	 * How long after login the client's Grand Exchange state burst is still
	 * arriving. Offer changes seen inside this window are marked approximate,
	 * because they may have happened while logged out or in another client
	 * rather than at the moment they were noticed.
	 */
	private static final long GE_SYNC_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(12);

	/** GE slots the client reports; once all have arrived the burst is over. */
	private static final int GE_SLOT_COUNT = 8;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private AccountAlmanacConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private AccountStore store;

	@Inject
	private HistoryStore historyStore;

	@Inject
	private GeEventStore geEventStore;

	@Inject
	private ScheduledExecutorService executor;

	private final BackupManager backupManager = new BackupManager();

	private AlmanacSidebarPanel panel;
	private NavigationButton navButton;
	private Long currentAccountHash;
	private ScheduledFuture<?> flushTask;
	private ScheduledFuture<?> maintenanceTask;
	private boolean needsNameUpdate;

	private long geSyncUntil;
	private int geSyncSlotsSeen;

	/**
	 * Whether the start-up reprice has actually produced prices yet this
	 * session.
	 *
	 * <p>RuneLite loads its price data asynchronously, so an attempt made while
	 * the plugin is starting can find nothing and silently do nothing. Being
	 * logged in guarantees the cache is warm, so the first login retries it -
	 * which is also the moment the figures are about to be looked at.
	 */
	private boolean repricedThisSession;

	/**
	 * Which account the periodic flush last refreshed a history point for, its
	 * wealth at the time, and when. See {@link #refreshSnapshotIfDue}. Touched
	 * only by the periodic flush, which always runs on the one executor thread.
	 */
	private Long snapshotAccountHash;
	private long snapshotWealth;
	private long snapshotRefreshedAt;

	@Override
	protected void startUp() throws Exception
	{
		currentAccountHash = null;
		geSyncUntil = 0L;
		geSyncSlotsSeen = 0;
		repricedThisSession = false;

		Format.applyConfig(config);
		LoginAge.applyConfig(config);

		panel = new AlmanacSidebarPanel(store, historyStore, geEventStore, config,
			configManager, this, itemManager, skillIconManager, spriteManager);

		store.loadAsync(() ->
		{
			SwingUtilities.invokeLater(panel::rebuild);
			refreshPricesOnStartup();
		});
		historyStore.loadAsync(() -> SwingUtilities.invokeLater(panel::rebuild));
		geEventStore.loadAsync(() -> SwingUtilities.invokeLater(panel::rebuild));

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Account Almanac")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Stat changes fire on every XP drop, far too often to save-to-disk
		// and rebuild the Swing panel on each one. AccountStore just marks
		// itself dirty on those; this periodically flushes to disk and
		// refreshes the UI at a sane, human-perceptible rate instead.
		flushTask = executor.scheduleWithFixedDelay(this::periodicFlush,
			FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

		maintenanceTask = executor.scheduleWithFixedDelay(this::runMaintenance,
			1, MAINTENANCE_INTERVAL_MINUTES, TimeUnit.MINUTES);

		if (config.backupOnSession())
		{
			// Queued rather than run inline: startUp must not touch disk.
			executor.execute(() -> backupNow());
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		if (flushTask != null)
		{
			flushTask.cancel(false);
			flushTask = null;
		}
		if (maintenanceTask != null)
		{
			maintenanceTask.cancel(false);
			maintenanceTask = null;
		}

		// A final snapshot so a session's progress is not lost because the
		// client closed between intervals. Cheap - it touches memory only.
		recordSnapshotForCurrentAccount();

		// The writes are queued rather than performed here. shutDown() runs on
		// the Swing thread when the plugin is toggled off in the config panel,
		// and three synchronous JSON writes there froze the whole client for as
		// long as the event log took to serialise. The guidelines are explicit
		// that shutDown must not block.
		//
		// Nothing is lost by queueing: the periodic flush runs every five
		// seconds, so at most that much is outstanding, and the executor is
		// RuneLite's own shared one which outlives this plugin being disabled.
		executor.execute(() ->
		{
			store.flushIfDirty();
			historyStore.flushIfDirty();
			geEventStore.flushIfDirty();
		});

		if (config.backupOnSession())
		{
			// Queued behind the flushes above so it copies the freshly written
			// files rather than the previous ones.
			executor.execute(this::backupNow);
		}

		if (panel != null)
		{
			panel.closeViewer();
		}
		panel = null;
		navButton = null;
		currentAccountHash = null;
		needsNameUpdate = false;
		geSyncUntil = 0L;
		geSyncSlotsSeen = 0;
	}

	/** Flushes all three stores and refreshes the panel if anything changed. */
	private void periodicFlush()
	{
		// Wrapped because scheduleWithFixedDelay treats an escaping exception as
		// fatal to the task - RuneLite's handler rethrows, so one bad tick would
		// silently stop all flushing and UI refresh for the rest of the session.
		// The underlying race is fixed, but nothing here is worth that failure
		// mode.
		try
		{
			flushOnce();
		}
		catch (RuntimeException e)
		{
			log.warn("Periodic flush failed; continuing", e);
		}
	}

	private void flushOnce()
	{
		long now = System.currentTimeMillis();
		boolean changed = refreshSnapshotIfDue(now);
		changed |= store.flushIfDue(now);
		changed |= historyStore.flushIfDirty();
		changed |= geEventStore.flushIfDirty();

		if (changed)
		{
			refreshPanel();
		}
	}

	/**
	 * Refreshes the logged-in account's history point from the periodic flush.
	 *
	 * <p>A newly logged-in account, or a change in its bank or Grand Exchange
	 * value, goes in straight away. XP movement alone waits for
	 * {@link AccountStore#SKILL_SAVE_INTERVAL_MILLIS}, the same cadence skill
	 * changes reach the account file at: XP moves on every drop, and refreshing
	 * each time rewrote the history file every few seconds while training.
	 * Logout and shutdown still record a point immediately.
	 */
	private boolean refreshSnapshotIfDue(long now)
	{
		Long hash = currentAccountHash;
		AccountRecord record = hash == null ? null : store.findAccount(hash);
		if (record == null)
		{
			return false;
		}

		long wealth = record.bankValue + record.geValue();
		if (hash.equals(snapshotAccountHash) && wealth == snapshotWealth
			&& now - snapshotRefreshedAt < AccountStore.SKILL_SAVE_INTERVAL_MILLIS)
		{
			return false;
		}

		snapshotAccountHash = hash;
		snapshotWealth = wealth;
		snapshotRefreshedAt = now;
		return historyStore.recordSnapshot(record, now, config.snapshotIntervalHours());
	}

	/**
	 * Backups and pruning. Runs hourly, but only acts when the configured
	 * interval has actually elapsed - {@link BackupManager} decides that from
	 * the existing backup folders rather than from a timer, so restarting the
	 * client does not reset the schedule.
	 */
	private void runMaintenance()
	{
		if (!config.autoBackup())
		{
			return;
		}
		try
		{
			File written = backupManager.backupIfDue(System.currentTimeMillis(), config.backupIntervalDays());
			if (written != null)
			{
				int pruned = backupManager.pruneOldBackups(config.backupsToKeep());
				log.debug("Backup written to {} ({} old backups pruned)", written.getName(), pruned);
			}
		}
		catch (RuntimeException e)
		{
			// A failed backup must never take the plugin down with it.
			log.warn("Automatic backup failed", e);
		}
	}

	/**
	 * Snapshots the logged-in account. {@link HistoryStore} applies the
	 * throttle, so calling this every flush is cheap and simply refreshes the
	 * tip of the account's history until the interval rolls over.
	 */
	private boolean recordSnapshotForCurrentAccount()
	{
		Long hash = currentAccountHash;
		if (hash == null)
		{
			return false;
		}
		AccountRecord record = store.findAccount(hash);
		if (record == null)
		{
			return false;
		}
		return historyStore.recordSnapshot(record, System.currentTimeMillis(), config.snapshotIntervalHours());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!AccountAlmanacConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		// Number formats and colours are read at render time, so adopting the
		// new values and repainting is all that is needed - no restart.
		Format.applyConfig(config);
		LoginAge.applyConfig(config);
		refreshPanel();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			long hash = client.getAccountHash();
			if (hash == -1L)
			{
				return;
			}
			currentAccountHash = hash;
			needsNameUpdate = true;

			// Prices are certainly loaded by now, unlike at plugin start-up.
			if (!repricedThisSession && config.refreshPricesOnStartup())
			{
				repricedThisSession = true;
				log.debug("Repricing stored items on first login of the session");
				refreshPrices(this::refreshPanel);
			}

			// Open the window during which GE offer changes are treated as
			// possibly-historic rather than as happening right now.
			geSyncUntil = System.currentTimeMillis() + GE_SYNC_WINDOW_MILLIS;
			geSyncSlotsSeen = 0;

			tryUpdateDisplayName();
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			// Capture where the account finished before letting go of it.
			boolean wasLoggedIn = currentAccountHash != null;
			recordSnapshotForCurrentAccount();
			if (wasLoggedIn)
			{
				// Saved now rather than by the periodic flush: skill changes
				// otherwise wait up to AccountStore.SKILL_SAVE_INTERVAL_MILLIS,
				// and a client closed from the login screen should not depend on
				// that timer. Queued, because this runs on the client thread.
				executor.execute(() ->
				{
					store.flushIfDirty();
					historyStore.flushIfDirty();
				});
			}
			currentAccountHash = null;
			needsNameUpdate = false;
			geSyncUntil = 0L;
			geSyncSlotsSeen = 0;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// The local player (and its name) isn't always populated yet on the
		// exact tick GameStateChanged fires LOGGED_IN - keep retrying each
		// tick until it actually shows up, instead of a single attempt that
		// can silently lose the race and leave the account unnamed.
		if (needsNameUpdate)
		{
			tryUpdateDisplayName();
			tryUpdatePlaytime();
			tryUpdateQuestPoints();
		}
	}

	private void tryUpdateDisplayName()
	{
		if (currentAccountHash == null)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		String name = local != null ? local.getName() : null;
		if (name != null && !name.isEmpty())
		{
			store.touchAccount(currentAccountHash, name, readLoginName(), readAccountType());
			needsNameUpdate = false;
			refreshPanel();
		}
	}

	/**
	 * Best-effort login identity.
	 *
	 * <p>{@code getLauncherDisplayName()} is the account name as assigned by
	 * the Jagex launcher - this is what {@code ConfigManager} itself uses
	 * internally to key per-account config profiles, so it is the correct,
	 * currently-supported way to identify a Jagex account. It is preferred.
	 *
	 * <p>There used to be a fallback to {@code getUsername()}, which reads the
	 * client's own login screen field for accounts not using the launcher. It
	 * was removed: on the classic login flow that field holds whatever was
	 * typed to log in, which is frequently an email address, and a tracker has
	 * no business recording one. The launcher name is an account label and
	 * carries no such risk.
	 *
	 * <p>This can come back blank on a non-launcher login. An empty result is
	 * discarded by the store rather than overwriting a value captured earlier,
	 * and it is never inferred from the display name - a guessed login name
	 * would be worse than no login name at all.
	 */
	private String readLoginName()
	{
		return readLauncherName();
	}

	/** The Jagex launcher's account name, which is available before login too. */
	private String readLauncherName()
	{
		String launcherName = client.getLauncherDisplayName();
		return launcherName == null ? "" : launcherName.trim();
	}

	/**
	 * Ironman variant, read from the game's own account-type varbit.
	 *
	 * <p>Replaces the deprecated {@code Client.getAccountType()}, which read
	 * this same varbit. The names returned are the ones that method's enum
	 * used, so values stored by earlier versions keep matching.
	 */
	private String readAccountType()
	{
		switch (client.getVarbitValue(VarbitID.IRONMAN))
		{
			case 1:
				return "IRONMAN";
			case 2:
				return "ULTIMATE_IRONMAN";
			case 3:
				return "HARDCORE_IRONMAN";
			case 4:
				return "GROUP_IRONMAN";
			case 5:
				return "HARDCORE_GROUP_IRONMAN";
			default:
				return "NORMAL";
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (currentAccountHash == null || event.getContainerId() != InventoryID.BANK)
		{
			return;
		}

		ItemContainer container = event.getItemContainer();
		if (container == null)
		{
			return;
		}

		// Already on the client thread here, so ItemManager lookups are safe.
		// Names and prices are captured now and stored alongside the
		// quantities, so the panel can render banks for accounts that aren't
		// logged in without touching the client at all.
		Map<Integer, BankItem> merged = new LinkedHashMap<>();
		long value = 0L;

		for (Item item : container.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				// Placeholders sit at quantity 0 and would otherwise show up
				// as zero-value rows in every aggregate view.
				continue;
			}

			int canonicalId = itemManager.canonicalize(item.getId());
			int unitPrice = itemManager.getItemPrice(canonicalId);
			value += (long) unitPrice * item.getQuantity();

			BankItem existing = merged.get(canonicalId);
			if (existing != null)
			{
				// Noted and unnoted stacks of the same item canonicalise to
				// one id, so they have to be summed rather than overwrite.
				existing.quantity += item.getQuantity();
			}
			else
			{
				net.runelite.api.ItemComposition composition = itemManager.getItemComposition(canonicalId);
				merged.put(canonicalId, new BankItem(canonicalId, item.getQuantity(),
					composition.getName(), unitPrice, composition.getHaPrice(),
					composition.isMembers()));
			}
		}

		store.updateBank(currentAccountHash, new ArrayList<>(merged.values()), value);
		refreshPanel();
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (currentAccountHash == null)
		{
			return;
		}

		GrandExchangeOffer offer = event.getOffer();
		if (offer == null)
		{
			return;
		}

		GrandExchangeRecord record = new GrandExchangeRecord();
		record.slot = event.getSlot();
		record.state = offer.getState().name();
		record.itemId = offer.getItemId();
		record.pricePerItem = offer.getPrice();
		record.totalQuantity = offer.getTotalQuantity();
		record.quantitySold = offer.getQuantitySold();
		record.spent = offer.getSpent();
		record.updatedAt = System.currentTimeMillis();

		if (record.itemId > 0)
		{
			record.itemName = itemManager.getItemComposition(record.itemId).getName();
			// Captured now, on the client thread, so stock in a sell offer is
			// valued at what it is worth rather than at what it was listed for.
			record.marketPrice = itemManager.getItemPrice(record.itemId);
		}

		// Swap in the new state and get back what was there, so the change can
		// be turned into log events. Atomic inside the store - see updateGeOffer.
		GrandExchangeRecord previous = store.updateGeOffer(currentAccountHash, record);

		if (config.logGeEvents())
		{
			logGeChange(previous, record);
		}
	}

	/**
	 * Turns one slot change into log events. The item name is copied from the
	 * new record onto events derived from the old one, because a collected
	 * slot reports item id 0 and the name would otherwise be lost.
	 */
	private void logGeChange(GrandExchangeRecord previous, GrandExchangeRecord current)
	{
		boolean approximate = consumeGeSyncSlot();

		List<GeEvent> events = GeEventTracker.diff(
			previous, current,
			currentAccountHash,
			store.labelFor(currentAccountHash),
			System.currentTimeMillis(),
			approximate);

		if (events.isEmpty())
		{
			return;
		}

		// Fill in any item name the diff could not resolve from the record it
		// was derived from.
		for (GeEvent geEvent : events)
		{
			if (geEvent.itemName.isEmpty() && geEvent.itemId > 0)
			{
				geEvent.itemName = itemManager.getItemComposition(geEvent.itemId).getName();
			}
		}

		geEventStore.append(events, config.maxGeEvents());
	}

	/**
	 * Whether the post-login state burst is still arriving, counting this
	 * offer event against it. Named for the fact that it mutates: each call
	 * consumes one slot of the burst allowance.
	 *
	 * <p>The burst ends when all eight slots have reported once or when the
	 * window elapses, whichever comes first - the slot count handles the
	 * common case immediately, and the timeout covers an account that never
	 * reports eight slots.
	 */
	private boolean consumeGeSyncSlot()
	{
		if (geSyncUntil == 0L)
		{
			return false;
		}
		if (geSyncSlotsSeen < GE_SLOT_COUNT)
		{
			geSyncSlotsSeen++;
			return System.currentTimeMillis() < geSyncUntil;
		}
		geSyncUntil = 0L;
		return false;
	}

	/**
	 * Captures the account's reported playtime.
	 *
	 * <p>Read on a tick rather than once at login because the varp is not
	 * populated the instant the game state flips - the same race the display
	 * name has. The store ignores zero, so an early read costs nothing.
	 */
	private void tryUpdatePlaytime()
	{
		if (currentAccountHash == null)
		{
			return;
		}
		store.updatePlaytime(currentAccountHash,
			client.getVarbitValue(VarbitID.ACCOUNT_SUMMARY_DISPLAY_PLAYTIME));
	}

	/**
	 * Captures the account's quest points and the game's current maximum.
	 *
	 * <p>Read on a tick for the same reason as playtime: neither var is
	 * populated on the exact tick the game state flips to logged in. The
	 * store ignores a zero maximum, so an early read costs nothing.
	 *
	 * <p>The maximum comes from the game rather than a constant in here,
	 * so a quest release moves the denominator without a plugin update.
	 */
	private void tryUpdateQuestPoints()
	{
		if (currentAccountHash == null)
		{
			return;
		}
		store.updateQuestPoints(currentAccountHash,
			client.getVarpValue(VarPlayerID.QP),
			client.getVarbitValue(VarbitID.QP_MAX));
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (currentAccountHash == null)
		{
			return;
		}
		// getLevel() is the real level; getBoostedLevel() is transient and
		// would be meaningless once the account is logged out.
		store.updateSkill(currentAccountHash, event.getSkill().name(),
			event.getLevel(), event.getXp());
	}

	/**
	 * Re-prices every stored bank snapshot against current GE prices.
	 *
	 * <p>Snapshots are taken at different times, so without this an
	 * account's value reflects prices from whenever its bank was last
	 * opened - which makes cross-account totals compare stale numbers
	 * against fresh ones. Pricing needs the client thread, so this hops
	 * there, then goes back off-thread before touching disk.
	 *
	 * @param onComplete run on the Swing thread once prices are applied
	 */
	void refreshPrices(Runnable onComplete)
	{
		Set<Integer> ids = store.allBankItemIds();
		if (ids.isEmpty())
		{
			SwingUtilities.invokeLater(onComplete);
			return;
		}

		clientThread.invoke(() ->
		{
			Map<Integer, Integer> prices = new HashMap<>(ids.size());
			// Alch values come from the item composition, which is available for
			// any id on this thread - the bank does not need to have been
			// opened. Collected here so every stored item gets one, rather than
			// only the ones seen since alch values started being recorded.
			Map<Integer, Integer> alchPrices = new HashMap<>(ids.size());
			// Members flag comes from the same composition lookup, so stored
			// items from before it was recorded get one without a second pass.
			Map<Integer, Boolean> membersById = new HashMap<>(ids.size());
			for (Integer id : ids)
			{
				net.runelite.api.ItemComposition comp = itemManager.getItemComposition(id);
				membersById.put(id, comp.isMembers());
				int ha = comp.getHaPrice();
				if (ha > 0)
				{
					alchPrices.put(id, ha);
				}
				int price = itemManager.getItemPrice(id);
				// A zero is never published. It means one of two things and
				// they are indistinguishable here: an untradeable item, which
				// already stores 0 and so needs no update, or an item cache
				// that is not warm yet, which is transient. Writing it would
				// overwrite a good stored price with nothing - and run at
				// start-up across forty accounts, that empties every bank.
				// Omitting the key makes applyPrices keep what it has.
				if (price > 0)
				{
					prices.put(id, price);
				}
			}

			executor.execute(() ->
			{
				// Baselines are updated before the new prices land, so the
				// comparison is against what was there previously.
				if (historyStore.updatePriceBaselines(prices, System.currentTimeMillis(),
					TimeUnit.HOURS.toMillis(config.priceMovementHours())))
				{
					historyStore.flushIfDirty();
				}

				store.applyAlchPrices(alchPrices);
				store.applyMembersFlags(membersById);

				if (store.applyPrices(prices))
				{
					store.flushIfDirty();
					// Every account's wealth was just restated at current
					// prices. That is genuinely new information about all of
					// them, not just the one logged in, so it is worth a
					// history point for each.
					snapshotAllAccounts();
				}
				SwingUtilities.invokeLater(onComplete);
			});
		});
	}

	/**
	 * Brings every stored price up to date when the client starts.
	 *
	 * <p>Snapshots are taken whenever a bank happened to be open, so without
	 * this the totals mix prices from different days - an account banked last
	 * week is compared against one banked an hour ago. Repricing on start-up
	 * means every figure shown is against the same market.
	 *
	 * <p>When the setting is off this still runs for offers that have no market
	 * price at all, since their stock would otherwise be valued at nothing.
	 * That case is a one-off after upgrading and normally does nothing.
	 */
	private void refreshPricesOnStartup()
	{
		if (config.refreshPricesOnStartup())
		{
			log.debug("Repricing all stored items against current prices");
			refreshPrices(() ->
			{
				// Only counts as done if prices were actually available; the
				// login retry covers the case where they were not.
				repricedThisSession = store.hasPricedItems();
				refreshPanel();
			});
			return;
		}

		for (AccountRecord record : store.getAccounts())
		{
			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (offer.needsMarketPrice())
				{
					log.debug("Pricing stored Grand Exchange offers for the first time");
					refreshPrices(this::refreshPanel);
					return;
				}
			}
		}
	}

	/**
	 * Records a history snapshot for every account with something worth
	 * recording. Called after a reprice rather than on a timer, so history
	 * only gains points when the underlying figures actually moved.
	 */
	private void snapshotAllAccounts()
	{
		long now = System.currentTimeMillis();
		int interval = config.snapshotIntervalHours();
		boolean any = false;
		for (AccountRecord record : store.getAccounts())
		{
			any |= historyStore.recordSnapshot(record, now, interval);
		}
		if (any)
		{
			historyStore.flushIfDirty();
		}
	}

	/** Runs a backup immediately, for the settings tab's manual button. */
	File backupNow()
	{
		// Flush first, or the backup captures the last saved state rather than
		// the current one.
		store.flushIfDirty();
		historyStore.flushIfDirty();
		geEventStore.flushIfDirty();

		File written = backupManager.backupNow(System.currentTimeMillis());
		if (written != null)
		{
			backupManager.pruneOldBackups(config.backupsToKeep());
		}
		return written;
	}

	BackupManager backups()
	{
		return backupManager;
	}

	/** Account hash to display label, for chart legends. */
	Map<Long, String> accountLabels()
	{
		Map<Long, String> labels = new HashMap<>();
		for (AccountRecord record : store.getAccounts())
		{
			labels.put(record.accountHash, NameMasker.display(record, config.namePrivacy()));
		}
		return labels;
	}

	/** The account currently logged in, or {@code null} at the login screen. */
	Long currentAccountHash()
	{
		return currentAccountHash;
	}

	private void refreshPanel()
	{
		AlmanacSidebarPanel current = panel;
		if (current != null)
		{
			SwingUtilities.invokeLater(current::rebuild);
		}
	}

	@Provides
	AccountAlmanacConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AccountAlmanacConfig.class);
	}
}
