package com.accountalmanac;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The "AIO bank viewer" window: every tracked account side by side, every
 * item summed across all of them, every outstanding Grand Exchange offer,
 * and the per-account split of whichever item is selected.
 *
 * <p>This lives in its own resizable frame rather than the sidebar because
 * a {@code PluginPanel} is fixed at roughly 225px wide - enough for an
 * account summary, nowhere near enough for sortable tables next to a chart.
 *
 * <p>Reads only already-captured data, so it keeps working while logged
 * out. The two client-dependent bits degrade rather than fail: item sprites
 * simply do not render if {@code ItemManager} is unavailable, and the price
 * refresh is marshalled onto the client thread by the plugin itself.
 */
class WealthViewerFrame extends JFrame
{
	/** Tall enough for a 32px item sprite plus padding. */
	private static final int ICON_ROW_HEIGHT = 34;

	private final AccountStore store;
	private final HistoryStore historyStore;
	private final AccountAlmanacConfig config;
	private final AccountAlmanacPlugin plugin;
	private final ItemManager itemManager;
	private final SkillIconManager skillIconManager;

	private final GeLogPanel geLogPanel;
	private final SettingsPanel settingsPanel;

	/**
	 * Roster filter shared by every tab. Kept on the frame rather than per-tab
	 * so switching tabs does not silently change which accounts you are
	 * looking at.
	 */
	private final JComboBox<String> groupBox = new JComboBox<>();
	private boolean populatingGroups;

	/** Timeframe selectors for the two tabs that compare against history. */
	private final JComboBox<Timeframe> statsTimeframeBox = new JComboBox<>(Timeframe.values());
	private final JComboBox<Timeframe> wealthTimeframeBox = new JComboBox<>(Timeframe.values());
	private final JLabel statsTimeframeNote = new JLabel();
	private final JLabel wealthTimeframeNote = new JLabel();
	private final JLabel reminderLabel = new JLabel();

	private final AccountTableModel accountModel = new AccountTableModel();
	private final ItemTableModel itemModel = new ItemTableModel();
	private final OfferTableModel offerModel = new OfferTableModel();
	private final StatsTableModel statsModel = new StatsTableModel();
	private final RankedSkillTableModel rankedModel = new RankedSkillTableModel();
	private final JComboBox<String> statsAccountBox = new JComboBox<>();
	private final JCheckBox showXpBox = new JCheckBox("Show XP instead of levels");
	private final List<AccountRecord> statsAccounts = new ArrayList<>();
	private StatsGridPanel statsGrid;
	private boolean populatingStatsBox;
	private JTabbedPane tabs;
	private int statsTabIndex;

	private final JTable itemTable = new JTable(itemModel);
	private final JTable offerTable = new JTable(offerModel);
	private final TableRowSorter<ItemTableModel> itemSorter = new TableRowSorter<>(itemModel);

	private final JTextField searchField = new JTextField(18);
	private final JCheckBox showEmptySlots = new JCheckBox("Show empty slots");

	private final PieChartPanel itemSplitChart = new PieChartPanel(false);
	private final PieChartPanel wealthChart = new PieChartPanel(true);

	private final JLabel summaryLabel = new JLabel();
	private final JLabel offerSummaryLabel = new JLabel();
	private final JLabel itemSplitTitle = new JLabel("Select an item", SwingConstants.CENTER);
	private final JButton refreshButton = new JButton("Refresh prices");

	private final JLabel totalXpLabel = new JLabel();
	private final JLabel totalLevelSumLabel = new JLabel();
	private final JLabel ironmanBreakdownLabel = new JLabel();
	private final JLabel geActivityLabel = new JLabel();
	private final JLabel dataCompletenessLabel = new JLabel();
	private final JLabel topCombatLabel = new JLabel();
	private final JLabel topTotalLevelLabel = new JLabel();
	private final JLabel bondsLabel = new JLabel();
	private final SkillTotalsTableModel skillTotalsModel = new SkillTotalsTableModel();

	private final ItemIconCache itemIcons;
	private final ItemIconCache offerIcons;

	private final WealthChangeTableModel wealthChangeModel = new WealthChangeTableModel();
	private final SnapshotTableModel snapshotModel = new SnapshotTableModel();
	private static final String ALL_ACCOUNTS_OPTION = "All accounts (combined)";
	private final JComboBox<String> snapshotAccountBox = new JComboBox<>();
	private final List<AccountRecord> snapshotAccounts = new ArrayList<>();
	private boolean populatingSnapshotBox;

	private List<ItemAggregator.ItemTotal> currentTotals = new ArrayList<>();

	/**
	 * The accounts every aggregate view is built from: the current group
	 * selection with hidden accounts already removed. Held as a field because
	 * the timeframe controls recompute against it without a full reload.
	 */
	private List<AccountRecord> visibleAccounts = new ArrayList<>();

	WealthViewerFrame(AccountStore store, HistoryStore historyStore, GeEventStore geEventStore,
		AccountAlmanacConfig config, net.runelite.client.config.ConfigManager configManager,
		AccountAlmanacPlugin plugin, ItemManager itemManager,
		SkillIconManager skillIconManager)
	{
		super("Account Almanac");
		this.store = store;
		this.historyStore = historyStore;
		this.config = config;
		this.plugin = plugin;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		this.statsGrid = new StatsGridPanel(skillIconManager);
		this.itemIcons = new ItemIconCache(itemManager, itemTable);
		this.offerIcons = new ItemIconCache(itemManager, offerTable);
		this.geLogPanel = new GeLogPanel(geEventStore);
		this.settingsPanel = new SettingsPanel(config, configManager, plugin, store,
			historyStore, geEventStore, this::reload);

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setSize(1100, 680);
		setMinimumSize(new Dimension(780, 480));
		setLocationRelativeTo(null);

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.add(buildToolbar(), BorderLayout.NORTH);

		tabs = new JTabbedPane();
		tabs.addTab("Accounts", buildAccountsTab());
		tabs.addTab("All items", buildItemsTab());
		tabs.addTab("Grand Exchange", buildOffersTab());
		tabs.addTab("GE log", geLogPanel);
		statsTabIndex = tabs.getTabCount();
		tabs.addTab("Stats", buildStatsTab());
		tabs.addTab("Wealth split", buildWealthTab());
		tabs.addTab("Wealth history", buildHistoryTab());
		tabs.addTab("Interesting", buildInterestingTab());
		tabs.addTab("Settings", settingsPanel);
		root.add(tabs, BorderLayout.CENTER);

		setContentPane(root);
	}

	/** The name to show for an account, with the privacy setting applied. */
	private String nameOf(AccountRecord record)
	{
		return NameMasker.display(record, config.namePrivacy());
	}

	/**
	 * The name to show for a logged Grand Exchange event.
	 *
	 * <p>Events store the label as text captured when they were written, so the
	 * owning account has to be looked up to mask it. An event whose account has
	 * since been removed still must not fall back to the stored real name while
	 * masking is on - that is exactly the leak the setting exists to prevent.
	 */
	private String labelForEvent(GeEvent event)
	{
		AccountRecord owner = store.findAccount(event.accountHash);
		if (owner != null)
		{
			return nameOf(owner);
		}
		return config.namePrivacy().isMasked() ? "Removed account" : event.accountLabel;
	}

	/**
	 * Switches to the Stats tab and selects the given account, so a
	 * double-click on an account elsewhere in the UI can jump straight to
	 * its stats rather than making the user find and select it manually.
	 */
	void showStats(long accountHash)
	{
		tabs.setSelectedIndex(statsTabIndex);
		for (int i = 0; i < statsAccounts.size(); i++)
		{
			if (statsAccounts.get(i).accountHash == accountHash)
			{
				statsAccountBox.setSelectedIndex(i);
				return;
			}
		}
	}

	private JPanel buildToolbar()
	{
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		summaryLabel.setFont(FontManager.getRunescapeBoldFont());
		summaryLabel.setForeground(ColorScheme.BRAND_ORANGE);

		refreshButton.setToolTipText(
			"Re-price every stored bank against current GE prices. Snapshots are taken "
				+ "at different times, so totals can otherwise mix stale and fresh prices.");
		refreshButton.addActionListener(e -> onRefreshPrices());

		groupBox.setToolTipText("Limit every tab to one grouping of accounts");
		groupBox.addActionListener(e ->
		{
			if (!populatingGroups)
			{
				reload();
			}
		});

		reminderLabel.setToolTipText("Accounts that have not been logged into recently");

		bar.add(summaryLabel);
		bar.add(Box.createHorizontalStrut(16));
		bar.add(refreshButton);
		bar.add(Box.createHorizontalStrut(16));
		bar.add(new JLabel("Group:"));
		bar.add(groupBox);
		bar.add(Box.createHorizontalStrut(16));
		bar.add(reminderLabel);
		return bar;
	}

	/**
	 * Rebuilds the group dropdown, preserving the selection where it survives.
	 * Mirrors the sidebar's own list so the two offer the same choices.
	 */
	private void refreshGroupOptions(List<AccountRecord> accounts)
	{
		List<String> groups = AccountFilter.groupsFor(accounts);
		if (groups.size() == groupBox.getItemCount())
		{
			boolean same = true;
			for (int i = 0; i < groups.size(); i++)
			{
				if (!groups.get(i).equals(groupBox.getItemAt(i)))
				{
					same = false;
					break;
				}
			}
			if (same)
			{
				return;
			}
		}

		String selected = (String) groupBox.getSelectedItem();
		populatingGroups = true;
		try
		{
			groupBox.setModel(new javax.swing.DefaultComboBoxModel<>(groups.toArray(new String[0])));
			groupBox.setSelectedItem(groups.contains(selected) ? selected : AccountFilter.ALL_VISIBLE);
		}
		finally
		{
			populatingGroups = false;
		}
	}

	private void refreshReminderLabel(List<AccountRecord> accounts, long now)
	{
		List<LoginReminders.Reminder> due = LoginReminders.overdue(accounts, now,
			config.reminderAfterDays(), config.remindOnlyWithOffers(), config.remindIncludeBanned());

		if (due.isEmpty())
		{
			reminderLabel.setText("No accounts overdue");
			reminderLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			reminderLabel.setToolTipText("Every account has been logged into within "
				+ config.reminderAfterDays() + " days");
			return;
		}

		reminderLabel.setText(due.size() + " overdue for login");
		reminderLabel.setForeground(config.staleLoginColour());

		StringBuilder tip = new StringBuilder("<html>");
		int shown = 0;
		for (LoginReminders.Reminder reminder : due)
		{
			if (shown++ == 20)
			{
				tip.append("...and ").append(due.size() - 20).append(" more");
				break;
			}
			tip.append(AlmanacSidebarPanel.escape(reminder.describe(config.namePrivacy()))).append("<br>");
		}
		tip.append("</html>");
		reminderLabel.setToolTipText(tip.toString());
	}

	private void onRefreshPrices()
	{
		refreshButton.setEnabled(false);
		refreshButton.setText("Refreshing...");
		plugin.refreshPrices(() ->
		{
			refreshButton.setEnabled(true);
			refreshButton.setText("Refresh prices");
			reload();
		});
	}

	private JScrollPane buildAccountsTab()
	{
		JTable table = new JTable(accountModel);
		table.setAutoCreateRowSorter(true);
		table.setRowHeight(24);
		table.setFillsViewportHeight(true);
		table.setToolTipText("Double-click a row to view its stats");
		setRenderer(table, gpRenderer(), 8, 9, 10);
		// Group (3) and Type (4) both name the account type, so both get the helm.
		setRenderer(table, accountTypeIconRenderer(), 3, 4);
		// Last login and days-since are the two columns the green/yellow/red
		// thresholds apply to, so both share one renderer.
		setRenderer(table, loginAgeRenderer(), AccountTableModel.COL_LAST_LOGIN,
			AccountTableModel.COL_DAYS);
		table.getColumnModel().getColumn(AccountTableModel.COL_LAST_LOGIN).setPreferredWidth(120);

		table.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() != 2)
				{
					return;
				}
				int viewRow = table.rowAtPoint(e.getPoint());
				if (viewRow < 0)
				{
					return;
				}
				AccountRecord record = accountModel.recordAt(table.convertRowIndexToModel(viewRow));
				if (record != null)
				{
					showStats(record.accountHash);
				}
			}
		});

		return new JScrollPane(table);
	}

	private JSplitPane buildItemsTab()
	{
		itemTable.setRowSorter(itemSorter);
		itemTable.setRowHeight(ICON_ROW_HEIGHT);
		itemTable.setFillsViewportHeight(true);
		itemTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		setRenderer(itemTable, gpRenderer(), 2);
		setRenderer(itemTable, gpRenderer(), 3);
		setRenderer(itemTable, quantityRenderer(), 1);
		setRenderer(itemTable, new ItemCellRenderer(itemIcons,
			row -> row >= 0 && row < currentTotals.size()
				? currentTotals.get(row).itemId : null), 0);
		itemTable.getColumnModel().getColumn(0).setPreferredWidth(230);

		// Sort by total value descending by default - the expensive stuff is
		// what anyone opening this actually wants to see first.
		itemSorter.toggleSortOrder(3);
		itemSorter.toggleSortOrder(3);

		itemTable.getSelectionModel().addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				updateItemSplit();
			}
		});

		searchField.setToolTipText("Filter items by name");
		searchField.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				applyFilter();
			}
		});

		JPanel left = new JPanel(new BorderLayout(0, 4));
		JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		searchRow.add(new JLabel("Search:"));
		searchRow.add(searchField);
		left.add(searchRow, BorderLayout.NORTH);
		left.add(new JScrollPane(itemTable), BorderLayout.CENTER);

		JPanel right = new JPanel(new BorderLayout(0, 6));
		right.setBackground(ColorScheme.DARK_GRAY_COLOR);
		right.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		itemSplitTitle.setFont(FontManager.getRunescapeBoldFont());
		itemSplitTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		itemSplitChart.setEmptyMessage("Select an item to see its split");
		right.add(itemSplitTitle, BorderLayout.NORTH);
		right.add(itemSplitChart, BorderLayout.CENTER);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		split.setResizeWeight(0.62);
		split.setDividerLocation(650);
		return split;
	}

	private JPanel buildOffersTab()
	{
		offerTable.setAutoCreateRowSorter(true);
		offerTable.setRowHeight(ICON_ROW_HEIGHT);
		offerTable.setFillsViewportHeight(true);

		setRenderer(offerTable, gpRenderer(), 5, 6, 7);
		setRenderer(offerTable, new ItemCellRenderer(offerIcons,
			row -> offerModel.itemIdAt(row)), 3);
		offerTable.getColumnModel().getColumn(0).setPreferredWidth(130);
		offerTable.getColumnModel().getColumn(3).setPreferredWidth(190);
		offerTable.getColumnModel().getColumn(4).setPreferredWidth(140);

		showEmptySlots.setToolTipText(
			"Include the GE slots that are not holding an offer");
		showEmptySlots.addActionListener(e -> reload());

		offerSummaryLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		controls.add(showEmptySlots);
		controls.add(Box.createHorizontalStrut(12));
		controls.add(offerSummaryLabel);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.add(controls, BorderLayout.NORTH);
		wrapper.add(new JScrollPane(offerTable), BorderLayout.CENTER);
		return wrapper;
	}

	private JSplitPane buildStatsTab()
	{
		// Left: the in-game stats interface, rebuilt. Right: the same
		// numbers as sortable text, which is what the grid cannot give you.
		statsAccountBox.addActionListener(e ->
		{
			if (!populatingStatsBox)
			{
				updateStatsSelection();
			}
		});

		statsTimeframeBox.setSelectedItem(config.defaultTimeframe());
		statsTimeframeBox.setToolTipText(
			"Compare current experience against a snapshot from this far back");
		statsTimeframeBox.addActionListener(e -> updateStatsSelection());

		statsTimeframeNote.setFont(FontManager.getRunescapeSmallFont());
		statsTimeframeNote.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		JPanel selectorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		selectorRow.add(new JLabel("Account:"));
		selectorRow.add(statsAccountBox);
		selectorRow.add(new JLabel("Gained:"));
		selectorRow.add(statsTimeframeBox);

		JPanel gridWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
		gridWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gridWrapper.add(statsGrid);

		JPanel selectorColumn = new JPanel(new BorderLayout());
		selectorColumn.setOpaque(false);
		selectorColumn.add(selectorRow, BorderLayout.NORTH);
		selectorColumn.add(statsTimeframeNote, BorderLayout.SOUTH);

		JPanel left = new JPanel(new BorderLayout());
		left.setBackground(ColorScheme.DARK_GRAY_COLOR);
		left.add(selectorColumn, BorderLayout.NORTH);
		left.add(new JScrollPane(gridWrapper), BorderLayout.CENTER);

		JTable ranked = new JTable(rankedModel);
		ranked.setAutoCreateRowSorter(true);
		ranked.setRowHeight(22);
		ranked.setFillsViewportHeight(true);
		setRenderer(ranked, countRenderer(), 1, 2);
		setRenderer(ranked, changeRenderer(true), 3);
		// Highest first is the whole point of this view.
		ranked.getRowSorter().toggleSortOrder(1);
		ranked.getRowSorter().toggleSortOrder(1);

		JTable matrix = new JTable(statsModel);
		matrix.setAutoCreateRowSorter(true);
		matrix.setRowHeight(22);
		matrix.setFillsViewportHeight(true);
		// 28 narrow columns will not fit a window width, so let the table
		// keep its natural width and scroll horizontally instead.
		matrix.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		showXpBox.setToolTipText(
			"Swap every skill column between level and total experience");
		showXpBox.addActionListener(e ->
		{
			statsModel.setShowXp(showXpBox.isSelected());
			rankedModel.fireTableDataChanged();
		});

		JPanel matrixWrapper = new JPanel(new BorderLayout());
		JPanel matrixControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		matrixControls.add(showXpBox);
		matrixWrapper.add(matrixControls, BorderLayout.NORTH);
		matrixWrapper.add(new JScrollPane(matrix), BorderLayout.CENTER);

		JTabbedPane textViews = new JTabbedPane();
		textViews.addTab("Ranked", new JScrollPane(ranked));
		textViews.addTab("All accounts", matrixWrapper);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, textViews);
		split.setResizeWeight(0.34);
		split.setDividerLocation(280);
		return split;
	}

	/**
	 * Points the grid and the ranked table at whichever account the selector
	 * names. Falls back to the first account when the previous selection has
	 * gone - a removed account, say - rather than rendering an empty grid.
	 */
	private void updateStatsSelection()
	{
		int index = statsAccountBox.getSelectedIndex();
		if (index < 0 || index >= statsAccounts.size())
		{
			index = statsAccounts.isEmpty() ? -1 : 0;
		}

		AccountRecord selected = index < 0 ? null : statsAccounts.get(index);
		statsGrid.setRecord(selected);

		Timeframe timeframe = (Timeframe) statsTimeframeBox.getSelectedItem();
		if (timeframe == null)
		{
			timeframe = Timeframe.ALL_TIME;
		}

		long now = System.currentTimeMillis();
		AccountHistory history = selected == null
			? null
			: historyStore.historyFor(selected.accountHash);

		rankedModel.setRecord(selected,
			selected == null ? null : TimeframeStats.skillXpDeltas(selected, history, timeframe, now));

		statsTimeframeNote.setText(describeCoverage(
			selected == null ? null : TimeframeStats.forAccount(selected, history, timeframe, now),
			timeframe));
	}

	/**
	 * Explains what a timeframe is actually measuring. A window the history
	 * does not cover still shows real numbers, measured from the oldest
	 * snapshot available - saying so is the difference between an honest
	 * partial answer and a misleading one.
	 */
	private static String describeCoverage(TimeframeStats.Change change, Timeframe timeframe)
	{
		if (change == null)
		{
			return " ";
		}
		if (change.empty)
		{
			return "No history recorded yet - gains will appear once snapshots accumulate.";
		}
		if (timeframe.isAllTime() || change.covered)
		{
			return "Measured from " + LoginAge.exact(change.baselineAt) + ".";
		}
		return "Only " + LoginAge.describeAge(change.baselineAt, System.currentTimeMillis())
			+ " of history - measuring from " + LoginAge.exact(change.baselineAt)
			+ ", not the full " + timeframe.label().toLowerCase(Locale.ROOT) + ".";
	}

	/**
	 * The wealth pie alongside per-account change over the selected window.
	 *
	 * <p>The pie always shows the split as it stands right now - a pie of
	 * "change" would be meaningless once any account has lost money, since a
	 * negative slice cannot be drawn. The timeframe therefore drives the table
	 * beside it, which can show negatives properly.
	 */
	private JPanel buildWealthTab()
	{
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		JLabel title = new JLabel("Total wealth by account", SwingConstants.CENTER);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		wealthTimeframeBox.setSelectedItem(config.defaultTimeframe());
		wealthTimeframeBox.setToolTipText(
			"Compare current wealth against a snapshot from this far back");
		wealthTimeframeBox.addActionListener(e -> refreshWealthChanges());

		wealthTimeframeNote.setFont(FontManager.getRunescapeSmallFont());
		wealthTimeframeNote.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		controls.setOpaque(false);
		controls.add(new JLabel("Change over:"));
		controls.add(wealthTimeframeBox);
		controls.add(wealthTimeframeNote);

		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		header.add(title, BorderLayout.NORTH);
		header.add(controls, BorderLayout.SOUTH);

		JTable changeTable = new JTable(wealthChangeModel);
		changeTable.setAutoCreateRowSorter(true);
		changeTable.setRowHeight(22);
		changeTable.setFillsViewportHeight(true);
		setRenderer(changeTable, gpRenderer(), 1, 2);
		setRenderer(changeTable, changeRenderer(false), 3);
		setRenderer(changeTable, percentRenderer(), 4);
		// Biggest gain first.
		changeTable.getRowSorter().toggleSortOrder(3);
		changeTable.getRowSorter().toggleSortOrder(3);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
			wealthChart, new JScrollPane(changeTable));
		split.setResizeWeight(0.35);
		split.setDividerLocation(280);

		wealthChart.setEmptyMessage("No wealth recorded yet");

		wrapper.add(header, BorderLayout.NORTH);
		wrapper.add(split, BorderLayout.CENTER);
		return wrapper;
	}

	/** Recomputes the per-account wealth change table for the selected window. */
	private void refreshWealthChanges()
	{
		Timeframe timeframe = (Timeframe) wealthTimeframeBox.getSelectedItem();
		if (timeframe == null)
		{
			timeframe = Timeframe.ALL_TIME;
		}

		long now = System.currentTimeMillis();
		Map<Long, AccountHistory> histories = TimeframeStats.index(historyStore.getHistories());
		List<TimeframeStats.AccountChange> ranked =
			TimeframeStats.rankByWealthChange(visibleAccounts, histories, timeframe, now);

		wealthChangeModel.setRows(ranked, config.namePrivacy());
		wealthTimeframeNote.setText(describeCoverage(
			TimeframeStats.forRoster(visibleAccounts, histories, timeframe, now), timeframe));
	}

	/** One decimal place with a percent sign, coloured like a change. */
	private DefaultTableCellRenderer percentRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				if (value instanceof Number)
				{
					double percent = ((Number) value).doubleValue();
					setText(String.format(Locale.ROOT, "%+.1f%%", percent));
					if (!selected)
					{
						if (percent > 0.0)
						{
							setForeground(config.gainColour());
						}
						else if (percent < 0.0)
						{
							setForeground(config.lossColour());
						}
						else
						{
							setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
						}
					}
				}
				setHorizontalAlignment(SwingConstants.RIGHT);
				return this;
			}
		};
	}

	/** Per-account wealth now, versus the start of the selected window. */
	private static class WealthChangeTableModel extends AbstractTableModel
	{
		private NamePrivacy privacy = NamePrivacy.REAL;

		private static final String[] COLUMNS = {
			"Account", "Wealth then", "Wealth now", "Change", "Change %"
		};

		private List<TimeframeStats.AccountChange> rows = new ArrayList<>();

		void setRows(List<TimeframeStats.AccountChange> rows, NamePrivacy privacy)
		{
			this.privacy = privacy;
			this.rows = rows;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			switch (column)
			{
				case 1:
				case 2:
				case 3:
					return Long.class;
				case 4:
					return Double.class;
				default:
					return String.class;
			}
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			TimeframeStats.AccountChange entry = rows.get(row);
			switch (column)
			{
				case 0:
					return NameMasker.display(entry.record, privacy);
				case 1:
					return entry.change.baselineWealth;
				case 2:
					return entry.change.currentWealth;
				case 3:
					return entry.change.wealthDelta();
				case 4:
					return entry.change.wealthPercent();
				default:
					return "";
			}
		}
	}

	/**
	 * The dated snapshots behind every timeframe figure.
	 *
	 * <p>The timeframe tabs only ever show a difference between two moments,
	 * which makes it impossible to see when anything actually happened. This
	 * lists the snapshots themselves, stamped with the PC's own local date and
	 * time, so a number can be traced back to the day it came from.
	 */
	private JPanel buildHistoryTab()
	{
		snapshotAccountBox.addActionListener(e ->
		{
			if (!populatingSnapshotBox)
			{
				refreshSnapshots();
			}
		});

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		controls.add(new JLabel("Account:"));
		controls.add(snapshotAccountBox);

		JTable table = new JTable(snapshotModel);
		table.setAutoCreateRowSorter(true);
		table.setRowHeight(22);
		table.setFillsViewportHeight(true);
		setRenderer(table, gpRenderer(), 2, 3, 4);
		setRenderer(table, changeRenderer(false), 5);
		setRenderer(table, countRenderer(), 6, 7);
		setRenderer(table, changeRenderer(true), 8);
		// Newest first; the date column sorts correctly as text because the
		// format is yyyy-MM-dd HH:mm.
		table.getRowSorter().toggleSortOrder(1);
		table.getRowSorter().toggleSortOrder(1);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.add(controls, BorderLayout.NORTH);
		wrapper.add(new JScrollPane(table), BorderLayout.CENTER);
		return wrapper;
	}

	/**
	 * Rebuilds the snapshot list for the current selection.
	 *
	 * <p>Index 0 is the whole roster; everything after it is one account, so
	 * the account list is offset by one.
	 */
	private void refreshSnapshots()
	{
		int index = snapshotAccountBox.getSelectedIndex();

		if (index <= 0)
		{
			List<AccountHistory> histories = new ArrayList<>();
			for (AccountRecord record : snapshotAccounts)
			{
				AccountHistory history = historyStore.historyFor(record.accountHash);
				if (history != null)
				{
					histories.add(history);
				}
			}
			snapshotModel.setCombined(histories);
			return;
		}

		AccountRecord selected = snapshotAccounts.get(index - 1);
		snapshotModel.setHistory(historyStore.historyFor(selected.accountHash));
	}

	/**
	 * One row per stored snapshot, oldest to newest, each with the change since
	 * the one before it. Numbered so a row can be referred to, and dated from
	 * the machine's own clock.
	 */
	private static class SnapshotTableModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {
			"#", "Date and time", "Bank", "GE", "Total wealth",
			"Change", "Total level", "Total XP", "XP gained"
		};

		private static class Row
		{
			int number;
			long at;
			long bank;
			long ge;
			long total;
			long change;
			int totalLevel;
			long totalXp;
			long xpGained;
		}

		private List<Row> rows = new ArrayList<>();

		/**
		 * Combines several accounts into one timeline.
		 *
		 * <p>Snapshots are not taken at the same instant on every account, so
		 * they cannot simply be zipped together. Instead each distinct day that
		 * any account recorded becomes a row, and every account contributes its
		 * most recent snapshot at or before the end of that day. That is the
		 * standard way to value a portfolio over time, and it avoids the obvious
		 * wrong answer - summing only the accounts that happened to snapshot on
		 * a given day, which would make the total collapse and rebound purely
		 * from who logged in.
		 */
		void setCombined(List<AccountHistory> histories)
		{
			java.util.TreeSet<Long> days = new java.util.TreeSet<>();
			for (AccountHistory history : histories)
			{
				for (HistorySnapshot snapshot : history.snapshots)
				{
					days.add(endOfDay(snapshot.at));
				}
			}

			List<Row> built = new ArrayList<>();
			Row previous = null;
			int number = 1;

			for (Long day : days)
			{
				Row row = new Row();
				row.number = number++;
				row.at = day;

				for (AccountHistory history : histories)
				{
					HistorySnapshot latest = null;
					for (HistorySnapshot snapshot : history.snapshots)
					{
						if (snapshot.at <= day)
						{
							latest = snapshot;
						}
						else
						{
							break; // sorted ascending
						}
					}
					if (latest != null)
					{
						row.bank += latest.bankValue;
						row.ge += latest.geValue;
						row.total += latest.totalWealth();
						row.totalLevel += latest.totalLevel;
						row.totalXp += latest.totalXp;
					}
				}

				row.change = previous == null ? 0L : row.total - previous.total;
				row.xpGained = previous == null ? 0L : row.totalXp - previous.totalXp;
				built.add(row);
				previous = row;
			}

			this.rows = built;
			fireTableDataChanged();
		}

		/** Last millisecond of the local day containing {@code at}. */
		private static long endOfDay(long at)
		{
			java.time.ZonedDateTime zoned = java.time.Instant.ofEpochMilli(at)
				.atZone(java.time.ZoneId.systemDefault());
			return zoned.toLocalDate().atTime(23, 59, 59)
				.atZone(java.time.ZoneId.systemDefault())
				.toInstant().toEpochMilli();
		}

		void setHistory(AccountHistory history)
		{
			List<Row> built = new ArrayList<>();
			if (history != null)
			{
				Row previous = null;
				int number = 1;
				for (HistorySnapshot snapshot : history.snapshots)
				{
					Row row = new Row();
					row.number = number++;
					row.at = snapshot.at;
					row.bank = snapshot.bankValue;
					row.ge = snapshot.geValue;
					row.total = snapshot.totalWealth();
					row.totalLevel = snapshot.totalLevel;
					row.totalXp = snapshot.totalXp;
					// The first snapshot has nothing before it, so it shows no
					// change rather than its whole value as a gain.
					row.change = previous == null ? 0L : row.total - previous.total;
					row.xpGained = previous == null ? 0L : row.totalXp - previous.totalXp;
					built.add(row);
					previous = row;
				}
			}
			this.rows = built;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			switch (column)
			{
				case 0:
				case 6:
					return Integer.class;
				case 2:
				case 3:
				case 4:
				case 5:
				case 7:
				case 8:
					return Long.class;
				default:
					return String.class;
			}
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			Row entry = rows.get(row);
			switch (column)
			{
				case 0:
					return entry.number;
				case 1:
					return LoginAge.exact(entry.at);
				case 2:
					return entry.bank;
				case 3:
					return entry.ge;
				case 4:
					return entry.total;
				case 5:
					return entry.change;
				case 6:
					return entry.totalLevel;
				case 7:
					return entry.totalXp;
				case 8:
					return entry.xpGained;
				default:
					return "";
			}
		}
	}

	/**
	 * Rollup stats across the whole tracked roster - things no single
	 * account's own view can show. Everything here comes from data already
	 * captured for other tabs; nothing new is recorded to build this.
	 */
	private JPanel buildInterestingTab()
	{
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		JPanel stats = new JPanel();
		stats.setLayout(new BoxLayout(stats, BoxLayout.Y_AXIS));
		stats.setOpaque(false);

		for (JLabel label : new JLabel[] {
			totalXpLabel, totalLevelSumLabel, bondsLabel, topTotalLevelLabel, topCombatLabel,
			ironmanBreakdownLabel, geActivityLabel, dataCompletenessLabel
		})
		{
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setAlignmentX(Component.LEFT_ALIGNMENT);
			label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
			stats.add(label);
		}
		totalXpLabel.setForeground(ColorScheme.BRAND_ORANGE);
		totalXpLabel.setFont(FontManager.getRunescapeBoldFont());

		JLabel tableTitle = new JLabel("Total XP by skill, across every tracked account");
		tableTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tableTitle.setFont(FontManager.getRunescapeBoldFont());
		tableTitle.setBorder(BorderFactory.createEmptyBorder(16, 0, 6, 0));
		tableTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		stats.add(tableTitle);

		JTable skillTable = new JTable(skillTotalsModel);
		skillTable.setAutoCreateRowSorter(true);
		skillTable.setRowHeight(22);
		setRenderer(skillTable, countRenderer(), 1, 2);
		// Highest total XP first - that ordering is the whole point of a
		// cross-account skill leaderboard.
		skillTable.getRowSorter().toggleSortOrder(2);
		skillTable.getRowSorter().toggleSortOrder(2);

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(stats, BorderLayout.NORTH);

		wrapper.add(top, BorderLayout.NORTH);
		wrapper.add(new JScrollPane(skillTable), BorderLayout.CENTER);
		return wrapper;
	}

	private void refreshInteresting(List<AccountRecord> accounts)
	{
		long totalXp = 0L;
		long totalLevelSum = 0L;
		AccountRecord topTotalLevel = null;
		AccountRecord topCombat = null;
		Map<String, Integer> typeCounts = new java.util.LinkedHashMap<>();
		int neverOpened = 0;

		for (AccountRecord record : accounts)
		{
			totalXp += record.totalXp();
			totalLevelSum += record.totalLevel();
			if (topTotalLevel == null || record.totalLevel() > topTotalLevel.totalLevel())
			{
				topTotalLevel = record;
			}
			if (topCombat == null || record.combatLevel > topCombat.combatLevel)
			{
				topCombat = record;
			}
			String type = record.accountType == null || record.accountType.isEmpty()
				? "Unknown" : AccountTypeBadge.fullLabel(record.accountType);
			typeCounts.merge(type, 1, Integer::sum);
			if (!record.hasBankSnapshot())
			{
				neverOpened++;
			}
		}

		totalXpLabel.setText("Total XP across all accounts: " + Format.exact(totalXp));
		totalLevelSumLabel.setText("Combined total level: " + Format.exact(totalLevelSum));

		StringBuilder types = new StringBuilder("Account types: ");
		boolean first = true;
		for (Map.Entry<String, Integer> entry : typeCounts.entrySet())
		{
			if (!first)
			{
				types.append(", ");
			}
			types.append(entry.getValue()).append(' ').append(entry.getKey());
			first = false;
		}
		ironmanBreakdownLabel.setText(accounts.isEmpty() ? "Account types: -" : types.toString());

		topTotalLevelLabel.setText(topTotalLevel == null ? "Highest total level: -"
			: "Highest total level: " + nameOf(topTotalLevel) + " (" + Format.exact(topTotalLevel.totalLevel()) + ")");
		topCombatLabel.setText(topCombat == null ? "Highest combat level: -"
			: "Highest combat level: " + nameOf(topCombat) + " (" + topCombat.combatLevel + ")");

		int activeOffers = 0;
		long committed = 0L;
		for (AccountRecord record : accounts)
		{
			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (offer.isActive())
				{
					activeOffers++;
					committed += offer.committedValue();
				}
			}
		}
		geActivityLabel.setText(String.format(Locale.ROOT,
			"Grand Exchange right now: %d active offer%s, %s committed",
			activeOffers, activeOffers == 1 ? "" : "s", Format.gp(committed)));

		updateBondsLabel(accounts);

		dataCompletenessLabel.setText(neverOpened == 0
			? "Every tracked account has had its bank opened at least once"
			: neverOpened + " of " + accounts.size() + " tracked accounts have never had their bank opened");

		skillTotalsModel.setAccounts(accounts, config.namePrivacy());
	}

	/**
	 * Bonds held across the roster.
	 *
	 * <p>Tradeable and untradeable are reported separately because only the
	 * former has a market price - lumping them together would imply the whole
	 * count could be sold. The accounts holding them are named in the tooltip,
	 * since the usual question after "how many" is "where".
	 */
	private void updateBondsLabel(List<AccountRecord> accounts)
	{
		Bonds.Holdings holdings = Bonds.count(accounts);

		if (holdings.isEmpty())
		{
			bondsLabel.setText("Bonds: none held");
			bondsLabel.setToolTipText(null);
			return;
		}

		StringBuilder text = new StringBuilder("Bonds: ")
			.append(holdings.total())
			.append(holdings.total() == 1 ? " bond" : " bonds")
			.append(" across ")
			.append(holdings.byAccount.size())
			.append(holdings.byAccount.size() == 1 ? " account" : " accounts");

		if (holdings.tradeable > 0 && holdings.untradeable > 0)
		{
			text.append("  (").append(holdings.tradeable).append(" tradeable, ")
				.append(holdings.untradeable).append(" untradeable)");
		}
		else if (holdings.untradeable > 0)
		{
			text.append("  (untradeable)");
		}

		if (holdings.tradeableValue > 0L)
		{
			text.append("  -  ").append(Format.gp(holdings.tradeableValue)).append(" tradeable value");
		}
		bondsLabel.setText(text.toString());

		StringBuilder tip = new StringBuilder("<html>");
		for (Bonds.AccountHolding holding : holdings.byAccount)
		{
			tip.append(AlmanacSidebarPanel.escape(nameOf(holding.record)))
				.append(": ").append(holding.count)
				.append(holding.count == 1 ? " bond" : " bonds")
				.append("<br>");
		}
		tip.append("</html>");
		bondsLabel.setToolTipText(tip.toString());
	}

	/**
	 * One row per skill, summed across every tracked account, with whichever
	 * account holds the most XP in that skill called out - the "who's the
	 * best woodcutter across my whole roster" view a single account's stats
	 * page can't answer.
	 */
	private static class SkillTotalsTableModel extends AbstractTableModel
	{
		private NamePrivacy privacy = NamePrivacy.REAL;

		private static final String[] COLUMNS = {"Skill", "Total level", "Total XP", "Top account", "Top account's XP"};

		/** One pre-computed row per skill - avoids re-scanning every account on every cell repaint/sort. */
		private static class Row
		{
			final String skillLabel;
			final long levelSum;
			final long xpSum;
			final String topAccountLabel;
			final long topAccountXp;

			Row(String skillLabel, long levelSum, long xpSum, String topAccountLabel, long topAccountXp)
			{
				this.skillLabel = skillLabel;
				this.levelSum = levelSum;
				this.xpSum = xpSum;
				this.topAccountLabel = topAccountLabel;
				this.topAccountXp = topAccountXp;
			}
		}

		private List<Row> rows = new ArrayList<>();

		void setAccounts(List<AccountRecord> accounts, NamePrivacy privacy)
		{
			this.privacy = privacy;
			List<Row> computed = new ArrayList<>();
			for (Skill skill : SkillOrder.panelOrder())
			{
				String skillName = skill.name();
				long levelSum = 0L;
				long xpSum = 0L;
				AccountRecord top = null;
				for (AccountRecord record : accounts)
				{
					levelSum += record.skillLevel(skillName);
					xpSum += record.skillXpFor(skillName);
					if (top == null || record.skillXpFor(skillName) > top.skillXpFor(skillName))
					{
						top = record;
					}
				}
				computed.add(new Row(SkillOrder.prettify(skillName), levelSum, xpSum,
					top == null ? "-" : NameMasker.display(top, privacy),
					top == null ? 0L : top.skillXpFor(skillName)));
			}
			this.rows = computed;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			switch (column)
			{
				case 1:
				case 2:
				case 4:
					return Long.class;
				default:
					return String.class;
			}
		}

		@Override
		public Object getValueAt(int rowIndex, int column)
		{
			Row row = rows.get(rowIndex);
			switch (column)
			{
				case 0:
					return row.skillLabel;
				case 1:
					return row.levelSum;
				case 2:
					return row.xpSum;
				case 3:
					return row.topAccountLabel;
				case 4:
					return row.topAccountXp;
				default:
					return "";
			}
		}
	}

	private void applyFilter()
	{
		String text = searchField.getText();
		if (text == null || text.trim().isEmpty())
		{
			itemSorter.setRowFilter(null);
			return;
		}
		// Quoted so a stray '(' or '*' in the search box is treated as text
		// rather than blowing up as a malformed regex.
		itemSorter.setRowFilter(RowFilter.regexFilter(
			"(?i)" + Pattern.quote(text.trim()), 0));
	}

	private void updateItemSplit()
	{
		int viewRow = itemTable.getSelectedRow();
		if (viewRow < 0)
		{
			itemSplitTitle.setText("Select an item");
			itemSplitChart.setSlices(new ArrayList<>(), config.maxChartSlices());
			return;
		}

		int modelRow = itemTable.convertRowIndexToModel(viewRow);
		if (modelRow < 0 || modelRow >= currentTotals.size())
		{
			return;
		}

		ItemAggregator.ItemTotal total = currentTotals.get(modelRow);
		Map<Long, String> labels = plugin.accountLabels();
		itemSplitTitle.setText(total.name + " - " + Format.exact(total.totalQuantity)
			+ " across " + total.accountCount()
			+ (total.accountCount() == 1 ? " account" : " accounts"));
		itemSplitChart.setSlices(
			ItemAggregator.splitForItem(total, labels), config.maxChartSlices());
	}

	/**
	 * Re-reads everything from the store and repaints. Cheap enough to call
	 * on every panel rebuild - it is a pass over in-memory records.
	 */
	void reload()
	{
		long now = System.currentTimeMillis();
		List<AccountRecord> all = store.getAccounts();
		boolean includeGe = config.includeGrandExchange();

		refreshGroupOptions(all);

		// One rule, applied once: the group selection narrows the roster, and
		// AccountFilter has already dropped hidden accounts unless the user
		// explicitly asked to see them. Every tab below builds on this list, so
		// hiding an account removes it from the totals everywhere rather than
		// from one table.
		List<AccountRecord> accounts = AccountFilter.apply(all, (String) groupBox.getSelectedItem());
		visibleAccounts = accounts;

		accountModel.setAccounts(accounts, includeGe, now, config.namePrivacy());
		statsModel.setAccounts(accounts, config.namePrivacy());
		refreshStatsSelector(accounts);
		refreshReminderLabel(all, now);

		List<ItemAggregator.ItemTotal> totals = ItemAggregator.aggregate(accounts);
		int floor = config.minimumItemValue();
		if (floor > 0)
		{
			List<ItemAggregator.ItemTotal> filtered = new ArrayList<>();
			for (ItemAggregator.ItemTotal total : totals)
			{
				if (total.totalValue >= floor)
				{
					filtered.add(total);
				}
			}
			totals = filtered;
		}

		currentTotals = totals;
		itemModel.setTotals(totals);

		reloadOffers(accounts);

		wealthChart.setSlices(ItemAggregator.wealthByAccount(accounts), config.maxChartSlices());

		long grand = 0L;
		for (AccountRecord record : accounts)
		{
			grand += includeGe ? record.totalWealth() : record.bankValue;
		}
		int hiddenCount = AccountFilter.hiddenCount(all);
		summaryLabel.setText(String.format(Locale.ROOT,
			"%s across %d account%s  -  %s distinct items%s",
			Format.gp(grand), accounts.size(), accounts.size() == 1 ? "" : "s",
			Format.exact(totals.size()),
			hiddenCount > 0 ? "   (" + hiddenCount + " hidden)" : ""));

		ThemeApplier.apply(getContentPane(), config.viewerTheme());

		updateItemSplit();
		refreshInteresting(accounts);
		refreshWealthChanges();
		refreshSnapshotSelector(accounts);
		geLogPanel.setLabelResolver(this::labelForEvent);
		geLogPanel.reload();
		settingsPanel.reload();
	}

	private void reloadOffers(List<AccountRecord> accounts)
	{
		List<OfferRow> rows = new ArrayList<>();
		int active = 0;
		long committed = 0L;

		for (AccountRecord record : accounts)
		{
			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (offer.isActive())
				{
					active++;
					committed += offer.committedValue();
				}
				else if (!showEmptySlots.isSelected())
				{
					continue;
				}
				rows.add(new OfferRow(nameOf(record), offer));
			}
		}

		offerModel.setRows(rows);
		offerSummaryLabel.setText(String.format(Locale.ROOT,
			"%d active offer%s  -  %s committed",
			active, active == 1 ? "" : "s", Format.gp(committed)));
	}

	private static void setRenderer(JTable table, DefaultTableCellRenderer renderer, int... columns)
	{
		for (int column : columns)
		{
			if (column < table.getColumnModel().getColumnCount())
			{
				table.getColumnModel().getColumn(column).setCellRenderer(renderer);
			}
		}
	}

	/**
	 * Coin amounts, coloured on the same scale the game uses for stacks - see
	 * {@link StackFormat}. Coins are an item stack in game and are coloured by
	 * exactly these thresholds there, so applying the same scale to every gp
	 * column makes the whole table read the way the game does.
	 *
	 * <p>The text still follows the user's chosen number format; only the
	 * colour comes from the game convention.
	 */
	private static DefaultTableCellRenderer gpRenderer()
	{
		return gpRenderer(null);
	}

	/**
	 * @param foreground fixed colour, or {@code null} to colour by the game's
	 *                   stack scale
	 */
	private static DefaultTableCellRenderer gpRenderer(Color foreground)
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				if (value instanceof Number)
				{
					long amount = ((Number) value).longValue();
					setText(Format.gp(amount));
					setToolTipText(Format.exact(amount) + " gp");
					if (!selected)
					{
						setForeground(foreground != null ? foreground : StackFormat.colour(amount));
					}
				}
				setHorizontalAlignment(SwingConstants.RIGHT);
				return this;
			}
		};
	}

	/**
	 * Grouped counts - 10,000 rather than 10000 - coloured on the game's stack
	 * scale so levels and XP read consistently with the item and coin columns.
	 */
	private static DefaultTableCellRenderer countRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				if (value instanceof Number)
				{
					long amount = ((Number) value).longValue();
					setText(Format.exact(amount));
					if (!selected)
					{
						setForeground(StackFormat.colour(amount));
					}
				}
				setHorizontalAlignment(SwingConstants.RIGHT);
				return this;
			}
		};
	}

	/**
	 * Colours the last-login columns by age, using the configured thresholds
	 * and colours. The colour is looked up per row from the record rather than
	 * parsed back out of the cell text, so the two columns cannot disagree.
	 *
	 * <p>Selected rows keep the table's selection foreground: overriding it
	 * would make a selected stale row unreadable against the highlight.
	 */
	private DefaultTableCellRenderer loginAgeRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);

				AccountRecord record = null;
				try
				{
					record = accountModel.recordAt(t.convertRowIndexToModel(row));
				}
				catch (IndexOutOfBoundsException e)
				{
					// Row vanished between sort and paint.
				}

				if (value instanceof Number)
				{
					long shown = ((Number) value).longValue();
					setText(shown < 0L ? "-" : Long.toString(shown));
					setHorizontalAlignment(SwingConstants.RIGHT);
				}
				else
				{
					setHorizontalAlignment(SwingConstants.LEFT);
				}

				long now = System.currentTimeMillis();
				if (record != null)
				{
					if (!selected)
					{
						setForeground(LoginAgeColours.forTimestamp(record.lastLoginAt, now, config));
					}
					setToolTipText(record.lastLoginAt > 0L
						? LoginAge.friendly(record.lastLoginAt)
							+ "  (" + LoginAge.describeAge(record.lastLoginAt, now) + ")"
						: "Never logged into since tracking began");
				}
				return this;
			}
		};
	}

	/**
	 * A signed change, coloured by the configured gain and loss colours.
	 *
	 * @param isXp {@code true} to format with the stat-gain number style,
	 *             {@code false} for the gp-change style - the two are
	 *             separately configurable
	 */
	private DefaultTableCellRenderer changeRenderer(boolean isXp)
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				if (value instanceof Number)
				{
					long delta = ((Number) value).longValue();
					setText(isXp ? Format.xpChange(delta) : Format.gpChange(delta));
					setToolTipText(Format.exactChange(delta));
					if (!selected)
					{
						if (delta > 0L)
						{
							setForeground(config.gainColour());
						}
						else if (delta < 0L)
						{
							setForeground(config.lossColour());
						}
						else
						{
							setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
						}
					}
				}
				setHorizontalAlignment(SwingConstants.RIGHT);
				return this;
			}
		};
	}

	/**
	 * Shows the game's helm icon beside a cell that names an account type.
	 *
	 * <p>Applied to both Group and Type, but keyed off the cell's own text
	 * rather than the record's type. Group usually shows the ironman type by
	 * inference, but the moment a custom grouping is set - a pure, a mule - it
	 * shows that instead, and a helm next to "Mule" would be nonsense. Matching
	 * the text means the icon appears exactly when the cell is actually naming
	 * the account type.
	 */
	private DefaultTableCellRenderer accountTypeIconRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				setIcon(null);

				AccountRecord record = null;
				try
				{
					record = accountModel.recordAt(t.convertRowIndexToModel(row));
				}
				catch (IndexOutOfBoundsException e)
				{
					// Row vanished between sort and paint.
				}

				if (record != null && value != null
					&& AccountTypeBadge.fullLabel(record.accountType).equals(value.toString()))
				{
					BufferedImage icon = AccountTypeBadge.icon(record.accountType);
					if (icon != null)
					{
						setIcon(new ImageIcon(icon));
					}
				}
				return this;
			}
		};
	}

	/**
	 * Item stack quantities exactly as the game renders them - see
	 * {@link StackFormat} for the ranges and colours. The exact grouped figure
	 * is always on the tooltip, since everything at or above 100,000 is
	 * truncated and therefore lossy.
	 */
	private static DefaultTableCellRenderer quantityRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				if (value instanceof Number)
				{
					long amount = ((Number) value).longValue();
					setText(StackFormat.text(amount));
					setToolTipText(Format.exact(amount));
					if (!selected)
					{
						setForeground(StackFormat.colour(amount));
					}
				}
				setHorizontalAlignment(SwingConstants.RIGHT);
				return this;
			}
		};
	}

	/** Resolves the item id backing a model row, for sprite lookup. */
	private interface IdLookup
	{
		Integer idAt(int modelRow);
	}

	/** Item name cell with its sprite alongside. */
	private static class ItemCellRenderer extends DefaultTableCellRenderer
	{
		private final ItemIconCache icons;
		private final IdLookup lookup;

		ItemCellRenderer(ItemIconCache icons, IdLookup lookup)
		{
			this.icons = icons;
			this.lookup = lookup;
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value,
			boolean selected, boolean focused, int row, int column)
		{
			super.getTableCellRendererComponent(table, value, selected, focused, row, column);
			Integer id = null;
			try
			{
				id = lookup.idAt(table.convertRowIndexToModel(row));
			}
			catch (IndexOutOfBoundsException e)
			{
				// Row vanished between sort and paint; render without a sprite.
			}
			setIcon(id == null ? null : icons.get(id));
			return this;
		}
	}

	/** One GE slot belonging to one account. */
	private static class OfferRow
	{
		final String accountLabel;
		final GrandExchangeRecord offer;

		OfferRow(String accountLabel, GrandExchangeRecord offer)
		{
			this.accountLabel = accountLabel;
			this.offer = offer;
		}
	}

	/**
	 * Every GE slot across every account, flattened into one table.
	 */
	private static class OfferTableModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {
			"Account", "Slot", "Type", "Item", "Progress",
			"Unit price", "Spent", "Committed", "Status"
		};

		private List<OfferRow> rows = new ArrayList<>();

		void setRows(List<OfferRow> rows)
		{
			this.rows = rows;
			fireTableDataChanged();
		}

		Integer itemIdAt(int modelRow)
		{
			if (modelRow < 0 || modelRow >= rows.size())
			{
				return null;
			}
			int id = rows.get(modelRow).offer.itemId;
			return id > 0 ? id : null;
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			switch (column)
			{
				case 1:
					return Integer.class;
				case 5:
				case 6:
				case 7:
					return Long.class;
				default:
					return String.class;
			}
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			OfferRow offerRow = rows.get(row);
			GrandExchangeRecord offer = offerRow.offer;
			switch (column)
			{
				case 0:
					return offerRow.accountLabel;
				case 1:
					return offer.slot + 1;
				case 2:
					return offer.typeLabel();
				case 3:
					return offer.isActive() ? offer.itemName : "-";
				case 4:
					return offer.isActive()
						? Format.exact(offer.quantitySold) + " / " + Format.exact(offer.totalQuantity)
						+ "  (" + Math.round(offer.progress() * 100) + "%)"
						: "-";
				case 5:
					return (long) offer.pricePerItem;
				case 6:
					return offer.spent;
				case 7:
					return offer.committedValue();
				case 8:
					return offer.stateLabel();
				default:
					return "";
			}
		}
	}

	/**
	 * One row per tracked account. Values are kept as boxed numbers so the
	 * table sorter orders them numerically; formatting happens in the
	 * renderer.
	 */
	private static class AccountTableModel extends AbstractTableModel
	{
		private NamePrivacy privacy = NamePrivacy.REAL;

		private static final String[] COLUMNS = {
			"Login name", "Display name", "Label", "Group", "Type", "Status",
			"Combat", "Total lvl", "Bank", "GE", "Total",
			"Last login", "Days", "Bank last seen"
		};

		/** Column indexes other code needs to address by name rather than number. */
		static final int COL_LAST_LOGIN = 11;
		static final int COL_DAYS = 12;

		private List<AccountRecord> rows = new ArrayList<>();
		private boolean includeGe = true;
		private long now = System.currentTimeMillis();

		void setAccounts(List<AccountRecord> accounts, boolean includeGe, long now, NamePrivacy privacy)
		{
			this.privacy = privacy;
			this.rows = accounts;
			this.includeGe = includeGe;
			this.now = now;
			fireTableDataChanged();
		}


		AccountRecord recordAt(int modelRow)
		{
			return modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			switch (column)
			{
				case 6:
				case 7:
				case 12:
					return Integer.class;
				case 8:
				case 9:
				case 10:
					return Long.class;
				default:
					return String.class;
			}
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			AccountRecord record = rows.get(row);
			switch (column)
			{
				case 0:
					// Blank under Jagex accounts, where the launcher logs in
					// rather than the client's own login screen.
					return NameMasker.displayField(
						record.loginName.isEmpty() ? "-" : record.loginName, record.accountHash, privacy);
				case 1:
					return NameMasker.display(record, privacy);
				case 2:
					return NameMasker.displayField(record.loginLabel, record.accountHash, privacy);
				case 3:
					return record.categoryLabel();
				case 4:
					return AccountTypeBadge.fullLabel(record.accountType);
				case 5:
				{
					// Flags rather than one exclusive state - an account can be
					// both banned and hidden.
					StringBuilder status = new StringBuilder();
					if (record.banned)
					{
						status.append("Banned");
					}
					if (record.hidden)
					{
						if (status.length() > 0)
						{
							status.append(", ");
						}
						status.append("Hidden");
					}
					return status.toString();
				}
				case 6:
					return record.combatLevel;
				case 7:
					return record.totalLevel();
				case 8:
					return record.bankValue;
				case 9:
					return record.geValue();
				case 10:
					return includeGe ? record.totalWealth() : record.bankValue;
				case 11:
					// Sorts correctly as text: the format is yyyy-MM-dd HH:mm,
					// so lexical and chronological order agree. "never" sorts
					// after every real date, which is where it belongs.
					return LoginAge.exact(record.lastLoginAt);
				case 12:
				{
					long days = LoginAge.daysSince(record.lastLoginAt, now);
					return days < 0L ? -1 : (int) Math.min(Integer.MAX_VALUE, days);
				}
				case 13:
					return record.hasBankSnapshot()
						? Format.relativeTime(record.lastSnapshotAt)
						: "never opened";
				default:
					return "";
			}
		}
	}

	/** Same preserve-by-hash rebuild as the stats selector. */
	private void refreshSnapshotSelector(List<AccountRecord> accounts)
	{
		Long previous = null;
		int index = snapshotAccountBox.getSelectedIndex();
		if (index > 0 && index - 1 < snapshotAccounts.size())
		{
			previous = snapshotAccounts.get(index - 1).accountHash;
		}

		populatingSnapshotBox = true;
		try
		{
			snapshotAccounts.clear();
			snapshotAccounts.addAll(accounts);
			snapshotAccountBox.removeAllItems();

			// Roster-wide first, since "how is everything doing" is the more
			// common question than any single account's history.
			snapshotAccountBox.addItem(ALL_ACCOUNTS_OPTION);

			int restore = 0;
			for (int i = 0; i < accounts.size(); i++)
			{
				AccountRecord record = accounts.get(i);
				snapshotAccountBox.addItem(nameOf(record));
				if (previous != null && record.accountHash == previous)
				{
					restore = i + 1;
				}
			}
			snapshotAccountBox.setSelectedIndex(restore);
		}
		finally
		{
			populatingSnapshotBox = false;
		}

		refreshSnapshots();
	}

	/**
	 * Rebuilds the account dropdown, preserving the current selection by
	 * account hash rather than by index - indexes shift as accounts are
	 * added, which would silently switch which account you are looking at.
	 */
	private void refreshStatsSelector(List<AccountRecord> accounts)
	{
		Long previous = null;
		int index = statsAccountBox.getSelectedIndex();
		if (index >= 0 && index < statsAccounts.size())
		{
			previous = statsAccounts.get(index).accountHash;
		}

		populatingStatsBox = true;
		try
		{
			statsAccounts.clear();
			statsAccounts.addAll(accounts);
			statsAccountBox.removeAllItems();

			int restore = 0;
			for (int i = 0; i < accounts.size(); i++)
			{
				AccountRecord record = accounts.get(i);
				statsAccountBox.addItem(nameOf(record));
				if (previous != null && record.accountHash == previous)
				{
					restore = i;
				}
			}
			if (!accounts.isEmpty())
			{
				statsAccountBox.setSelectedIndex(restore);
			}
		}
		finally
		{
			populatingStatsBox = false;
		}

		updateStatsSelection();
	}

	/**
	 * One row per skill for a single account - the view that answers "what
	 * is this account's highest skill", which the matrix cannot sort on.
	 */
	private static class RankedSkillTableModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {"Skill", "Level", "XP", "Gained"};

		private final List<Skill> order = SkillOrder.panelOrder();
		private AccountRecord record;

		/** Skill name to XP gained over the selected window; empty when unknown. */
		private Map<String, Long> gains = new java.util.HashMap<>();

		void setRecord(AccountRecord record, Map<String, Long> gains)
		{
			this.record = record;
			this.gains = gains == null ? new java.util.HashMap<>() : gains;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return record == null ? 0 : order.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			return column == 0 ? String.class : Long.class;
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			Skill skill = order.get(row);
			switch (column)
			{
				case 0:
					return SkillOrder.prettify(skill.name());
				case 1:
					return (long) record.skillLevel(skill.name());
				case 2:
					return (long) record.skillXpFor(skill.name());
				case 3:
				{
					Long gained = gains.get(skill.name());
					return gained == null ? 0L : gained;
				}
				default:
					return "";
			}
		}
	}

	/**
	 * One row per account, one column per skill.
	 *
	 * <p>The skill columns are derived from {@code Skill.values()} rather
	 * than hardcoded, so a skill added in a future game update gets a column
	 * without a code change. Only skills actually captured on some account
	 * are shown, which also drops any non-skill enum members.
	 */
	/**
	 * Every tracked account as rows, one column per skill in panel order.
	 * Toggling {@link #setShowXp} swaps every skill column between level and
	 * experience without changing which columns exist, so a saved sort order
	 * survives the toggle.
	 */
	private static class StatsTableModel extends AbstractTableModel
	{
		private NamePrivacy privacy = NamePrivacy.REAL;

		private static final String[] FIXED = {"Account", "Combat", "Total level"};

		private final List<Skill> skills = SkillOrder.panelOrder();
		private List<AccountRecord> rows = new ArrayList<>();
		private boolean showXp;

		void setAccounts(List<AccountRecord> accounts, NamePrivacy privacy)
		{
			this.privacy = privacy;
			this.rows = accounts;
			fireTableDataChanged();
		}

		void setShowXp(boolean showXp)
		{
			this.showXp = showXp;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return FIXED.length + skills.size();
		}

		@Override
		public String getColumnName(int column)
		{
			if (column < FIXED.length)
			{
				return FIXED[column];
			}
			return SkillOrder.prettify(skills.get(column - FIXED.length).name());
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			return column == 0 ? String.class : Long.class;
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			AccountRecord record = rows.get(row);
			switch (column)
			{
				case 0:
					return NameMasker.display(record, privacy);
				case 1:
					return (long) record.combatLevel;
				case 2:
					return (long) record.totalLevel();
				default:
					String skillName = skills.get(column - FIXED.length).name();
					return showXp
						? (long) record.skillXpFor(skillName)
						: (long) record.skillLevel(skillName);
			}
		}
	}

	/** One row per distinct item, summed across every account. */
	private static class ItemTableModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {
			"Item", "Quantity", "Unit price", "Total value", "Accounts"
		};

		private List<ItemAggregator.ItemTotal> rows = new ArrayList<>();

		void setTotals(List<ItemAggregator.ItemTotal> totals)
		{
			this.rows = totals;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			switch (column)
			{
				case 1:
				case 2:
				case 3:
					return Long.class;
				case 4:
					return Integer.class;
				default:
					return String.class;
			}
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			ItemAggregator.ItemTotal total = rows.get(row);
			switch (column)
			{
				case 0:
					return total.name;
				case 1:
					return total.totalQuantity;
				case 2:
					return (long) total.unitPrice;
				case 3:
					return total.totalValue;
				case 4:
					return total.accountCount();
				default:
					return "";
			}
		}
	}
}
