package com.accountalmanac;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.Comparator;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel: combined wealth across every tracked account, the most recent
 * login, any accounts overdue for one, a button that opens the full
 * {@link WealthViewerFrame}, filter and sort controls, then one
 * {@link AccountRowPanel} per account.
 *
 * <p>The sidebar deliberately stays a summary. A {@code PluginPanel} is
 * fixed at roughly 225px wide, so the item tables and charts live in the
 * separate viewer window instead.
 */
class AlmanacSidebarPanel extends PluginPanel
{
	/**
	 * Sort fields. Direction is a separate control rather than being baked into
	 * the list - "Wealth (high to low)" and "Wealth (low to high)" as two
	 * entries doubles the dropdown for every field added, and left "Recently
	 * updated" with no way to see the least recently updated at all, which is
	 * usually the more useful question.
	 */
	private static final String SORT_WEALTH = "Wealth";
	private static final String SORT_NAME = "Name";
	private static final String SORT_RECENT = "Recently updated";
	private static final String SORT_LAST_LOGIN = "Last login";
	private static final String SORT_COMBAT = "Combat level";
	private static final String SORT_TOTAL_LEVEL = "Total level";
	private static final String SORT_BANK_SEEN = "Bank last seen";

	/** Down-pointing triangle, the conventional "descending" marker. */
	private static final String ARROW_DESC = "▼";
	private static final String ARROW_ASC = "▲";

	private final AccountStore store;
	private final HistoryStore historyStore;
	private final GeEventStore geEventStore;
	private final AccountAlmanacConfig config;
	private final ConfigManager configManager;
	private final AccountAlmanacPlugin plugin;
	private final ItemManager itemManager;
	private final SkillIconManager skillIconManager;
	private final SpriteManager spriteManager;

	private final JLabel totalLabel = new JLabel();
	private final JLabel breakdownLabel = new JLabel();
	private final JLabel lastLoginLabel = new JLabel();
	private final JLabel reminderLabel = new JLabel();
	private final JButton viewerButton = new JButton("Open bank viewer");
	private final JPanel rowsContainer = new JPanel();
	private final JComboBox<String> groupBox = new JComboBox<>();
	private final JComboBox<String> sortBox = new JComboBox<>(new String[] {
		SORT_WEALTH, SORT_NAME, SORT_RECENT, SORT_LAST_LOGIN,
		SORT_COMBAT, SORT_TOTAL_LEVEL, SORT_BANK_SEEN
	});

	private final JButton sortDirectionButton = new JButton();

	/**
	 * Which way the chosen field sorts. Reset to the field's natural direction
	 * when the field changes - biggest wealth and most recent activity are what
	 * you almost always want first, but A to Z is the natural reading for a
	 * name, so a single global default would be wrong half the time.
	 */
	private boolean sortDescending = true;

	/**
	 * Guards the group dropdown against firing a rebuild while {@link #rebuild}
	 * is repopulating it, which would recurse.
	 */
	private boolean updatingGroups;

	private WealthViewerFrame viewer;

	AlmanacSidebarPanel(AccountStore store, HistoryStore historyStore, GeEventStore geEventStore,
		AccountAlmanacConfig config, ConfigManager configManager,
		AccountAlmanacPlugin plugin, ItemManager itemManager,
		SkillIconManager skillIconManager, SpriteManager spriteManager)
	{
		super();
		this.store = store;
		this.historyStore = historyStore;
		this.geEventStore = geEventStore;
		this.config = config;
		this.configManager = configManager;
		this.plugin = plugin;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);

		rowsContainer.setLayout(new BoxLayout(rowsContainer, BoxLayout.Y_AXIS));
		rowsContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(rowsContainer, BorderLayout.CENTER);
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(8, 8, 8, 8));

		totalLabel.setForeground(ColorScheme.BRAND_ORANGE);
		totalLabel.setFont(totalLabel.getFont().deriveFont(Font.BOLD, 15f));
		totalLabel.setAlignmentX(LEFT_ALIGNMENT);

		breakdownLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		breakdownLabel.setFont(FontManager.getRunescapeSmallFont());
		breakdownLabel.setAlignmentX(LEFT_ALIGNMENT);

		// The most recent login is the first thing asked for on the main tab,
		// so it sits directly under the headline total rather than in a table.
		lastLoginLabel.setFont(FontManager.getRunescapeSmallFont());
		lastLoginLabel.setAlignmentX(LEFT_ALIGNMENT);

		reminderLabel.setFont(FontManager.getRunescapeSmallFont());
		reminderLabel.setAlignmentX(LEFT_ALIGNMENT);

		viewerButton.setAlignmentX(LEFT_ALIGNMENT);
		viewerButton.setToolTipText("Open the full cross-account item table and charts");
		viewerButton.addActionListener(e -> openViewer());

		JPanel groupRow = new JPanel(new BorderLayout(4, 0));
		groupRow.setOpaque(false);
		groupRow.setAlignmentX(LEFT_ALIGNMENT);
		JLabel groupLabel = new JLabel("Group: ");
		groupLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		groupBox.addActionListener(e ->
		{
			if (!updatingGroups)
			{
				rebuild();
			}
		});
		groupRow.add(groupLabel, BorderLayout.WEST);
		groupRow.add(groupBox, BorderLayout.CENTER);

		JPanel sortRow = new JPanel(new BorderLayout(4, 0));
		sortRow.setOpaque(false);
		sortRow.setAlignmentX(LEFT_ALIGNMENT);
		JLabel sortLabel = new JLabel("Sort: ");
		sortLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sortBox.addActionListener(e ->
		{
			if (!updatingGroups)
			{
				sortDescending = defaultDescendingFor((String) sortBox.getSelectedItem());
				rebuild();
			}
		});

		// Plain UI font rather than the RuneScape one: the triangles are not in
		// the game font and would render as missing-glyph boxes.
		sortDirectionButton.setMargin(new Insets(1, 4, 1, 4));
		sortDirectionButton.addActionListener(e ->
		{
			sortDescending = !sortDescending;
			rebuild();
		});

		sortRow.add(sortLabel, BorderLayout.WEST);
		sortRow.add(sortBox, BorderLayout.CENTER);
		sortRow.add(sortDirectionButton, BorderLayout.EAST);

		header.add(totalLabel);
		header.add(Box.createVerticalStrut(2));
		header.add(breakdownLabel);
		header.add(Box.createVerticalStrut(4));
		header.add(lastLoginLabel);
		header.add(Box.createVerticalStrut(2));
		header.add(reminderLabel);
		header.add(Box.createVerticalStrut(8));
		header.add(viewerButton);
		header.add(Box.createVerticalStrut(8));
		header.add(groupRow);
		header.add(Box.createVerticalStrut(4));
		header.add(sortRow);

		return header;
	}

	private void openViewer()
	{
		ensureViewer();
		viewer.reload();
		showViewerWindow();
	}

	/** Opens the viewer already switched to one account's Stats tab. */
	private void openViewerToStats(long accountHash)
	{
		ensureViewer();
		viewer.reload();
		viewer.showStats(accountHash);
		showViewerWindow();
	}

	private void ensureViewer()
	{
		if (viewer == null)
		{
			viewer = new WealthViewerFrame(store, historyStore, geEventStore, config,
				configManager, plugin, itemManager, skillIconManager, spriteManager);
		}
	}

	private void showViewerWindow()
	{
		// No toFront() or requestFocus(): hub plugins may not change which
		// window is focused.
		viewer.setVisible(true);
	}

	/**
	 * Disposes the viewer window if it is open. Called from the plugin's
	 * {@code shutDown()} so disabling the plugin does not strand a frame
	 * holding a reference to the old store.
	 */
	void closeViewer()
	{
		WealthViewerFrame current = viewer;
		viewer = null;
		if (current != null)
		{
			SwingUtilities.invokeLater(current::dispose);
		}
	}

	void rebuild()
	{
		List<AccountRecord> all = store.getAccounts();
		long now = System.currentTimeMillis();
		boolean includeGe = config.includeGrandExchange();

		refreshGroupOptions(all);
		List<AccountRecord> accounts = AccountFilter.apply(all, (String) groupBox.getSelectedItem());

		// Totals follow the same rule everywhere: hidden accounts do not count.
		// Using the filtered list here would make the headline total change
		// whenever the user browsed a group, which is a filter, not a total.
		List<AccountRecord> counted = AccountFilter.visibleOnly(all);

		long bankTotal = 0L;
		long geTotal = 0L;
		for (AccountRecord record : counted)
		{
			bankTotal += record.bankValue;
			geTotal += record.geValue();
		}
		long grand = includeGe ? bankTotal + geTotal : bankTotal;

		totalLabel.setText(Format.gp(grand));
		totalLabel.setToolTipText(Format.exact(grand) + " gp");

		int hiddenCount = AccountFilter.hiddenCount(all);
		breakdownLabel.setText(String.format("across %d account%s%s%s",
			counted.size(),
			counted.size() == 1 ? "" : "s",
			includeGe && geTotal > 0 ? "  (" + Format.gp(geTotal) + " in GE)" : "",
			hiddenCount > 0 ? "  [" + hiddenCount + " hidden]" : ""));

		updateLastLoginLabel(all, now);
		updateReminderLabel(all, now);

		String sortField = (String) sortBox.getSelectedItem();
		updateSortDirectionButton(sortField);
		accounts.sort(comparatorFor(sortField, sortDescending));

		rowsContainer.removeAll();
		if (accounts.isEmpty())
		{
			JLabel empty = new JLabel("<html>No accounts tracked yet.<br><br>"
				+ "Log into an account through this client to add it, then open its "
				+ "bank to record the contents.</html>");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(new EmptyBorder(8, 8, 8, 8));
			rowsContainer.add(empty);
		}
		else
		{
			for (AccountRecord record : accounts)
			{
				long hash = record.accountHash;
				rowsContainer.add(new AccountRowPanel(record, store, config,
					this::rebuild, () -> openViewerToStats(hash), () -> removeAccount(hash)));
				rowsContainer.add(Box.createVerticalStrut(4));
			}
		}

		// Re-applied after the rows are rebuilt, since they are new components
		// each time and would otherwise come back in the default palette.
		ThemeApplier.apply(this, config.viewerTheme());

		rowsContainer.revalidate();
		rowsContainer.repaint();

		WealthViewerFrame current = viewer;
		if (current != null && current.isVisible())
		{
			current.reload();
		}
	}

	/**
	 * Deletes an account from all three stores. Kept here rather than in the
	 * row because the row only holds the account store - leaving history and
	 * GE events behind would resurrect a "removed" account's data the moment
	 * anything aggregated across those files.
	 */
	private void removeAccount(long accountHash)
	{
		store.removeAccount(accountHash);
		historyStore.removeAccount(accountHash);
		geEventStore.removeAccount(accountHash);
		rebuild();
	}

	/**
	 * The single most recently played account, stated in full local date and
	 * time. This is the "when was I last on" answer, so it is spelled out
	 * rather than abbreviated to a relative age.
	 */
	private void updateLastLoginLabel(List<AccountRecord> accounts, long now)
	{
		AccountRecord mostRecent = null;
		for (AccountRecord record : accounts)
		{
			if (record.lastLoginAt <= 0L)
			{
				continue;
			}
			if (mostRecent == null || record.lastLoginAt > mostRecent.lastLoginAt)
			{
				mostRecent = record;
			}
		}

		if (mostRecent == null)
		{
			lastLoginLabel.setText("No logins recorded yet");
			lastLoginLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			lastLoginLabel.setToolTipText(null);
			return;
		}

		// No <b> here. This label is set in the RuneScape small font, which
		// has no bold face, so Swing synthesises one by double-striking the
		// glyphs - which renders as a blur beside the crisp text around it.
		lastLoginLabel.setText("<html>Last login: "
			+ escape(NameMasker.display(mostRecent, config.namePrivacy())) + "<br>"
			+ LoginAge.friendly(mostRecent.lastLoginAt) + "</html>");
		lastLoginLabel.setForeground(LoginAgeColours.forTimestamp(mostRecent.lastLoginAt, now, config));
		lastLoginLabel.setToolTipText(LoginAge.describeAge(mostRecent.lastLoginAt, now)
			+ " - " + LoginAge.exact(mostRecent.lastLoginAt));
	}

	private void updateReminderLabel(List<AccountRecord> accounts, long now)
	{
		List<LoginReminders.Reminder> due = LoginReminders.overdue(accounts, now,
			config.reminderAfterDays(), config.remindOnlyWithOffers());

		if (due.isEmpty())
		{
			reminderLabel.setText("No accounts overdue");
			reminderLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			reminderLabel.setToolTipText(null);
			return;
		}

		reminderLabel.setText(due.size() + (due.size() == 1 ? " account" : " accounts")
			+ " overdue (" + config.reminderAfterDays() + "d+)");
		reminderLabel.setForeground(config.staleLoginColour());

		// The full list belongs in a tooltip rather than the sidebar; forty
		// accounts of reminders would push everything else off screen.
		StringBuilder tip = new StringBuilder("<html>");
		int shown = 0;
		for (LoginReminders.Reminder reminder : due)
		{
			if (shown++ == 12)
			{
				tip.append("...and ").append(due.size() - 12).append(" more");
				break;
			}
			tip.append(escape(reminder.describe(config.namePrivacy()))).append("<br>");
		}
		tip.append("</html>");
		reminderLabel.setToolTipText(tip.toString());
	}

	/**
	 * Repopulates the group dropdown, preserving the current selection when it
	 * still exists. Rebuilt on every refresh because categories appear and
	 * disappear as the user assigns them.
	 */
	private void refreshGroupOptions(List<AccountRecord> accounts)
	{
		List<String> groups = AccountFilter.groupsFor(accounts);
		String selected = (String) groupBox.getSelectedItem();

		if (matchesCurrentModel(groups))
		{
			return;
		}

		updatingGroups = true;
		try
		{
			groupBox.setModel(new DefaultComboBoxModel<>(groups.toArray(new String[0])));
			groupBox.setSelectedItem(groups.contains(selected) ? selected : AccountFilter.ALL_VISIBLE);
		}
		finally
		{
			updatingGroups = false;
		}
	}

	private boolean matchesCurrentModel(List<String> groups)
	{
		if (groupBox.getItemCount() != groups.size())
		{
			return false;
		}
		for (int i = 0; i < groups.size(); i++)
		{
			if (!groups.get(i).equals(groupBox.getItemAt(i)))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Ascending comparator for a field; the caller reverses it for descending.
	 * Building it one way round and flipping keeps the two directions exactly
	 * consistent, rather than two hand-written comparators that can disagree on
	 * tie-breaks.
	 */
	private static Comparator<AccountRecord> ascendingFor(String sortField)
	{
		if (SORT_NAME.equals(sortField))
		{
			return Comparator.comparing(AccountRecord::label, String.CASE_INSENSITIVE_ORDER);
		}
		if (SORT_RECENT.equals(sortField))
		{
			return Comparator.comparingLong(AccountRecord::lastActivityAt);
		}
		if (SORT_LAST_LOGIN.equals(sortField))
		{
			// Strictly the login timestamp, unlike "Recently updated" which
			// also counts bank snapshots.
			return Comparator.comparingLong(r -> r.lastLoginAt);
		}
		if (SORT_COMBAT.equals(sortField))
		{
			return Comparator.comparingInt(r -> r.combatLevel);
		}
		if (SORT_TOTAL_LEVEL.equals(sortField))
		{
			return Comparator.comparingInt(AccountRecord::totalLevel);
		}
		if (SORT_BANK_SEEN.equals(sortField))
		{
			return Comparator.comparingLong(r -> r.lastSnapshotAt);
		}
		return Comparator.comparingLong(AccountRecord::totalWealth);
	}

	private static Comparator<AccountRecord> comparatorFor(String sortField, boolean descending)
	{
		Comparator<AccountRecord> ascending = ascendingFor(sortField);
		return descending ? ascending.reversed() : ascending;
	}

	/** The direction a field is most often wanted in when first selected. */
	private static boolean defaultDescendingFor(String sortField)
	{
		// A to Z reads naturally for a name; everything else here is a number
		// or a date, where the largest or most recent is the interesting end.
		return !SORT_NAME.equals(sortField);
	}

	/**
	 * Points the arrow the right way and explains what it means for the field
	 * currently chosen - "newest first" and "highest first" are the same
	 * direction but not the same words, and an unlabelled triangle leaves the
	 * user guessing which.
	 */
	private void updateSortDirectionButton(String sortField)
	{
		sortDirectionButton.setText(sortDescending ? ARROW_DESC : ARROW_ASC);
		sortDirectionButton.setToolTipText("Sorted " + describeDirection(sortField)
			+ " - click to reverse");
	}

	private String describeDirection(String sortField)
	{
		if (SORT_NAME.equals(sortField))
		{
			return sortDescending ? "Z to A" : "A to Z";
		}
		if (SORT_RECENT.equals(sortField) || SORT_LAST_LOGIN.equals(sortField)
			|| SORT_BANK_SEEN.equals(sortField))
		{
			return sortDescending ? "most recent first" : "least recent first";
		}
		return sortDescending ? "highest first" : "lowest first";
	}

	/** Minimal escaping for the HTML-rendered labels above. */
	static String escape(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
