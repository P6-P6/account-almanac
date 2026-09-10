package com.accountalmanac;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The options tab inside the viewer window.
 *
 * <p>Every control writes straight through {@link ConfigManager} to the
 * plugin's own config group, which is what makes these settings survive a
 * client restart - the same store RuneLite's own config panel writes to, so
 * the two stay in agreement and neither overrides the other. Nothing is held
 * in a field here waiting to be applied; there is no "save" button because
 * there is nothing unsaved.
 *
 * <p>This duplicates the RuneLite config panel deliberately. The viewer is a
 * separate window that the user works in for long stretches, and having to go
 * back to the sidebar's gear icon to change a number format while looking at
 * the numbers is the kind of friction that makes a setting go unused.
 */
class SettingsPanel extends JPanel
{
	private final AccountAlmanacConfig config;
	private final ConfigManager configManager;
	private final AccountAlmanacPlugin plugin;
	private final AccountStore store;
	private final HistoryStore historyStore;
	private final GeEventStore geEventStore;
	private final Runnable onChanged;

	private final JTextArea dataSummary = new JTextArea(6, 60);

	/** Suppresses write-back while controls are being populated from config. */
	private boolean populating;

	SettingsPanel(AccountAlmanacConfig config, ConfigManager configManager,
		AccountAlmanacPlugin plugin, AccountStore store, HistoryStore historyStore,
		GeEventStore geEventStore, Runnable onChanged)
	{
		this.config = config;
		this.configManager = configManager;
		this.plugin = plugin;
		this.store = store;
		this.historyStore = historyStore;
		this.geEventStore = geEventStore;
		this.onChanged = onChanged;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		content.add(buildAppearanceSection());
		content.add(Box.createVerticalStrut(14));
		content.add(buildPrivacySection());
		content.add(Box.createVerticalStrut(14));
		content.add(buildNumbersSection());
		content.add(Box.createVerticalStrut(14));
		content.add(buildColoursSection());
		content.add(Box.createVerticalStrut(14));
		content.add(buildRemindersSection());
		content.add(Box.createVerticalStrut(14));
		content.add(buildHistorySection());
		content.add(Box.createVerticalStrut(14));
		content.add(buildGeSection());
		content.add(Box.createVerticalStrut(14));
		content.add(buildDataSection());

		add(new JScrollPane(content), BorderLayout.CENTER);
	}

	// ------------------------------------------------------------------
	// Sections
	// ------------------------------------------------------------------

	private JPanel buildPrivacySection()
	{
		JPanel grid = grid();

		JComboBox<NamePrivacy> privacyBox = new JComboBox<>(NamePrivacy.values());
		privacyBox.setSelectedItem(config.namePrivacy());
		privacyBox.addActionListener(e ->
		{
			if (!populating)
			{
				write("namePrivacy", privacyBox.getSelectedItem());
			}
		});

		addRow(grid, 0, "Account names", privacyBox,
			"Covers display names, login names and your own labels.");

		return section("Privacy",
			"For screenshots and screen sharing. This is display only - the stored roster, the "
				+ "Grand Exchange log and every backup keep the real names, so switching back to "
				+ "'Show real names' brings them straight back. Randomised stand-ins are stable "
				+ "per account, so charts and tables stay readable while masked.", grid);
	}

	private JPanel buildAppearanceSection()
	{
		JPanel grid = grid();

		JComboBox<ViewerTheme> themeBox = new JComboBox<>(ViewerTheme.values());
		themeBox.setSelectedItem(config.viewerTheme());
		themeBox.addActionListener(e ->
		{
			if (!populating)
			{
				write("viewerTheme", themeBox.getSelectedItem());
			}
		});

		addRow(grid, 0, "Theme", themeBox,
			"Applies to the sidebar panel and this window.");

		return section("Appearance",
			"Only this plugin's own panel and window change - the rest of RuneLite is left "
				+ "alone. 'RuneLite dark' is the default so the sidebar matches the client's "
				+ "other plugin panels out of the box.",
			grid);
	}

	private JPanel buildNumbersSection()
	{
		JPanel grid = grid();
		int row = 0;

		addRow(grid, row++, "Wealth",
			formatBox("wealthFormat", config.wealthFormat()),
			"Bank values, GE values and totals.");
		addRow(grid, row++, "GP changes",
			formatBox("gpChangeFormat", config.gpChangeFormat()),
			"Gains and losses over a timeframe.");
		addRow(grid, row++, "Stat gains and XP",
			formatBox("statGainFormat", config.statGainFormat()),
			"Experience totals and gains.");
		addRow(grid, row++, "Item quantities",
			formatBox("quantityFormat", config.quantityFormat()),
			"Item counts.");

		return section("Numbers",
			"237,700 or 237.7K, chosen separately per kind of value.", grid);
	}

	private JPanel buildColoursSection()
	{
		JPanel grid = grid();
		int row = 0;

		addRow(grid, row++, "Recent login", colourButton("freshLoginColour", config.freshLoginColour()),
			"Under the warning threshold.");
		addRow(grid, row++, "Getting stale", colourButton("warningLoginColour", config.warningLoginColour()),
			"At or past the warning threshold.");
		addRow(grid, row++, "Stale", colourButton("staleLoginColour", config.staleLoginColour()),
			"At or past the stale threshold.");
		addRow(grid, row++, "Gain", colourButton("gainColour", config.gainColour()),
			"Positive change over a timeframe.");
		addRow(grid, row, "Loss", colourButton("lossColour", config.lossColour()),
			"Negative change over a timeframe.");

		return section("Colours", "Applied across the sidebar, tables and charts.", grid);
	}

	private JPanel buildRemindersSection()
	{
		JPanel grid = grid();
		int row = 0;

		addRow(grid, row++, "Warn after", daySpinner("warnAfterDays", config.warnAfterDays(), 1, 365),
			"Days before an account is coloured as getting stale.");
		addRow(grid, row++, "Stale after", daySpinner("staleAfterDays", config.staleAfterDays(), 1, 365),
			"Days before an account is coloured as stale.");
		addRow(grid, row++, "Remind after", daySpinner("reminderAfterDays", config.reminderAfterDays(), 1, 365),
			"Days before an account is listed as needing a login.");

		JCheckBox onlyOffers = new JCheckBox("Only remind about accounts with live GE offers",
			config.remindOnlyWithOffers());
		onlyOffers.addActionListener(e -> write("remindOnlyWithOffers", onlyOffers.isSelected()));
		addRow(grid, row++, "", onlyOffers, "");

		return section("Login reminders",
			"Grand Exchange offers do not expire with age, but their prices go stale as the "
				+ "market moves - 14 days is a reasonable point to revisit one.", grid);
	}

	private JPanel buildHistorySection()
	{
		JPanel grid = grid();
		int row = 0;

		addRow(grid, row++, "Snapshot every",
			spinner("snapshotIntervalHours", config.snapshotIntervalHours(), 1, 168, " hours"),
			"Smallest gap between retained history points. Timeframe comparisons need these.");

		JCheckBox auto = new JCheckBox("Back up automatically", config.autoBackup());
		auto.addActionListener(e -> write("autoBackup", auto.isSelected()));
		addRow(grid, row++, "Backups", auto, "");

		addRow(grid, row++, "Back up every",
			daySpinner("backupIntervalDays", config.backupIntervalDays(), 1, 90), "");
		addRow(grid, row++, "Backups to keep",
			spinner("backupsToKeep", config.backupsToKeep(), 1, 50, ""),
			"Older backups beyond this are deleted, oldest first.");

		JButton backupNow = new JButton("Back up now");
		backupNow.addActionListener(e -> runBackupNow());
		JButton openFolder = new JButton("Show backup folder path");
		openFolder.addActionListener(e -> JOptionPane.showMessageDialog(this,
			plugin.backups().backupsDir().getAbsolutePath(),
			"Backup folder", JOptionPane.INFORMATION_MESSAGE));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttons.setOpaque(false);
		buttons.add(backupNow);
		buttons.add(openFolder);
		addRow(grid, row, "", buttons, "");

		return section("History and backups",
			"Snapshots power the 7 day / month / 90 day / year comparisons. Backups are plain "
				+ "copies inside the plugin's own folder.", grid);
	}

	private JPanel buildGeSection()
	{
		JPanel grid = grid();
		int row = 0;

		JCheckBox logging = new JCheckBox("Record Grand Exchange events", config.logGeEvents());
		logging.addActionListener(e -> write("logGeEvents", logging.isSelected()));
		addRow(grid, row++, "Logging", logging,
			"Offers started, finished and cancelled, plus items and coins collected.");

		addRow(grid, row, "Events to keep",
			spinner("maxGeEvents", config.maxGeEvents(), 500, 500000, ""),
			"Purchase and sale history read from this log, so a bigger number reaches further back.");

		return section("Grand Exchange log", "", grid);
	}

	private JPanel buildDataSection()
	{
		dataSummary.setEditable(false);
		dataSummary.setLineWrap(true);
		dataSummary.setWrapStyleWord(true);
		dataSummary.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dataSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		dataSummary.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setOpaque(false);
		wrapper.add(dataSummary, BorderLayout.CENTER);

		return section("Stored data",
			"Settings above are saved in RuneLite's config, so they follow a signed-in RuneLite "
				+ "account between machines. The tracked data below stays on this PC only - it is "
				+ "far too large for the config store, and it is a list of every account you own.",
			wrapper);
	}



	// ------------------------------------------------------------------
	// Refresh
	// ------------------------------------------------------------------

	/** How often the stored-data summary is actually recomputed. */
	private static final long SUMMARY_INTERVAL_MILLIS = 30_000L;

	private long summaryRefreshedAt;

	/**
	 * Updates the read-only summaries.
	 *
	 * <p>Called on the Swing thread whenever the viewer reloads, which is every
	 * five seconds while anything is dirty. Recomputing it costs a full scan of
	 * the event log holding the store's lock, plus a directory listing for the
	 * backups - neither of which belongs on a five-second loop for a panel of
	 * static text that is usually not even the visible tab. Throttled instead;
	 * the numbers being half a minute stale costs nothing.
	 */
	void reload()
	{
		long now = System.currentTimeMillis();
		if (summaryRefreshedAt != 0L && now - summaryRefreshedAt < SUMMARY_INTERVAL_MILLIS)
		{
			return;
		}
		summaryRefreshedAt = now;

		StringBuilder sb = new StringBuilder();
		sb.append("Folder: ").append(store.file().getParentFile().getAbsolutePath()).append('\n');
		sb.append("Accounts file: ").append(describeFile(store.file())).append('\n');
		sb.append("History file: ").append(describeFile(historyStore.file()))
			.append("  (").append(historyStore.totalSnapshotCount()).append(" snapshots");
		long earliest = historyStore.earliestSnapshotAt();
		if (earliest > 0L)
		{
			sb.append(", oldest ").append(LoginAge.exact(earliest));
		}
		sb.append(")\n");
		sb.append("GE event log: ").append(describeFile(geEventStore.file()))
			.append("  (").append(geEventStore.eventCount()).append(" events");
		long firstEvent = geEventStore.earliestEventAt();
		if (firstEvent > 0L)
		{
			sb.append(", oldest ").append(LoginAge.exact(firstEvent));
		}
		sb.append(")\n");

		List<File> backups = plugin.backups().listBackups();
		if (backups.isEmpty())
		{
			sb.append("Backups: none yet");
		}
		else
		{
			sb.append("Backups: ").append(backups.size())
				.append(", most recent ").append(backups.get(0).getName());
		}

		dataSummary.setText(sb.toString());
	}

	private static String describeFile(File file)
	{
		if (file == null || !file.exists())
		{
			return "not written yet";
		}
		double kb = file.length() / 1024.0;
		if (kb >= 1024.0)
		{
			return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
		}
		return String.format(Locale.ROOT, "%.0f KB", kb);
	}

	private void runBackupNow()
	{
		File written = plugin.backupNow();
		if (written == null)
		{
			JOptionPane.showMessageDialog(this,
				"Nothing to back up yet - no data files have been written.",
				"Backup", JOptionPane.INFORMATION_MESSAGE);
		}
		else
		{
			JOptionPane.showMessageDialog(this,
				"Backup written to:\n" + written.getAbsolutePath(),
				"Backup", JOptionPane.INFORMATION_MESSAGE);
		}
		reload();
	}

	// ------------------------------------------------------------------
	// Control builders
	// ------------------------------------------------------------------

	private JComboBox<NumberFormatMode> formatBox(String key, NumberFormatMode current)
	{
		JComboBox<NumberFormatMode> box = new JComboBox<>(NumberFormatMode.values());
		box.setSelectedItem(current);
		box.addActionListener(e ->
		{
			if (!populating)
			{
				write(key, box.getSelectedItem());
			}
		});
		return box;
	}

	private JSpinner daySpinner(String key, int current, int min, int max)
	{
		return spinner(key, current, min, max, " days");
	}

	private JSpinner spinner(String key, int current, int min, int max, String suffix)
	{
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(
			Math.max(min, Math.min(max, current)), min, max, 1));
		spinner.setPreferredSize(new Dimension(90, spinner.getPreferredSize().height));
		spinner.addChangeListener(e ->
		{
			if (!populating)
			{
				write(key, spinner.getValue());
			}
		});
		if (!suffix.isEmpty())
		{
			spinner.setToolTipText("Measured in" + suffix);
		}
		return spinner;
	}

	/**
	 * A button showing the current colour, opening a chooser on click. Painted
	 * as its own background rather than using a swatch icon so it stays legible
	 * at any look-and-feel.
	 */
	private JButton colourButton(String key, Color current)
	{
		JButton button = new JButton("          ");
		button.setBackground(current);
		button.setOpaque(true);
		button.setBorderPainted(true);
		button.setToolTipText("Click to change");
		button.addActionListener(e ->
		{
			Color chosen = JColorChooser.showDialog(this, "Choose colour", button.getBackground());
			if (chosen != null)
			{
				button.setBackground(chosen);
				write(key, chosen);
			}
		});
		return button;
	}

	/**
	 * Writes one setting. Going through {@code ConfigManager} rather than
	 * holding state here is what makes it persist and what makes the plugin's
	 * own {@code onConfigChanged} fire, which repaints everything already
	 * showing.
	 */
	private void write(String key, Object value)
	{
		configManager.setConfiguration(AccountAlmanacConfig.GROUP, key, value);
		if (onChanged != null)
		{
			onChanged.run();
		}
	}

	// ------------------------------------------------------------------
	// Layout helpers
	// ------------------------------------------------------------------

	private static JPanel grid()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		return panel;
	}

	private static void addRow(JPanel grid, int row, String label,
		java.awt.Component control, String hint)
	{
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 0, 3, 8);
		c.gridy = row;
		c.anchor = GridBagConstraints.WEST;

		c.gridx = 0;
		JLabel name = new JLabel(label);
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setPreferredSize(new Dimension(150, name.getPreferredSize().height));
		grid.add(name, c);

		c.gridx = 1;
		grid.add(control, c);

		if (!hint.isEmpty())
		{
			c.gridx = 2;
			c.weightx = 1.0;
			JLabel hintLabel = new JLabel(hint);
			hintLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			hintLabel.setFont(FontManager.getRunescapeSmallFont());
			grid.add(hintLabel, c);
		}
	}

	private static JPanel section(String title, String description, java.awt.Component body)
	{
		JPanel panel = new JPanel(new BorderLayout(0, 6));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 0, 10, 0)));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);

		JLabel titleLabel = new JLabel(title);
		titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setAlignmentX(LEFT_ALIGNMENT);
		header.add(titleLabel);

		if (!description.isEmpty())
		{
			JLabel descriptionLabel = new JLabel("<html><body style='width:640px'>"
				+ AlmanacSidebarPanel.escape(description) + "</body></html>");
			descriptionLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			descriptionLabel.setFont(FontManager.getRunescapeSmallFont());
			descriptionLabel.setAlignmentX(LEFT_ALIGNMENT);
			header.add(descriptionLabel);
		}

		panel.add(header, BorderLayout.NORTH);
		panel.add(body, BorderLayout.CENTER);
		return panel;
	}
}
