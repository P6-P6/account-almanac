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
import java.awt.Toolkit;
import java.util.HashMap;
import javax.swing.JButton;
import javax.swing.JDialog;
import java.io.File;
import java.io.IOException;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
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
import net.runelite.client.game.SpriteManager;
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

	/**
	 * Held so removing an account can clear its Grand Exchange log as well.
	 * Everything else reads events through {@link GeLogPanel}.
	 */
	private final GeEventStore geEventStore;
	private final AccountAlmanacConfig config;
	private final AccountAlmanacPlugin plugin;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
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
	private final JLabel riserLabel = new JLabel();
	private final JLabel dropperLabel = new JLabel();
	private final JLabel highAlchLabel = new JLabel();
	private final JLabel playtimeLabel = new JLabel();
	private final SkillTotalsTableModel skillTotalsModel = new SkillTotalsTableModel();
	private final JComboBox<String> membershipFilter =
		new JComboBox<>(new String[]{"All items", "Free-to-play", "Members"});
	private final CuriosityTableModel randomEventModel = new CuriosityTableModel(true);
	private final CuriosityTableModel burntModel = new CuriosityTableModel(false);
	private final JLabel randomEventSummary = new JLabel();
	private final JLabel burntSummary = new JLabel();

	private final ItemIconCache itemIcons;
	private final ItemIconCache offerIcons;

	/**
	 * Sprites for the Grand Exchange log's item column. Its own cache rather
	 * than a shared one because each is bound to the table it repaints.
	 */
	private final ItemIconCache geLogIcons;

	private final WealthChangeTableModel wealthChangeModel = new WealthChangeTableModel();
	private final SnapshotTableModel snapshotModel = new SnapshotTableModel();
	private static final String ALL_ACCOUNTS_OPTION = "All accounts (combined)";
	private final JComboBox<String> snapshotAccountBox = new JComboBox<>();
	private final List<AccountRecord> snapshotAccounts = new ArrayList<>();
	private boolean populatingSnapshotBox;
	private final JCheckBox snapshotExcludeGe =
		new JCheckBox("Bank only (exclude GE value)");

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
		SkillIconManager skillIconManager, SpriteManager spriteManager)
	{
		super("Account Almanac");
		this.store = store;
		this.historyStore = historyStore;
		this.geEventStore = geEventStore;
		this.config = config;
		this.plugin = plugin;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.skillIconManager = skillIconManager;
		this.statsGrid = new StatsGridPanel(skillIconManager);
		this.itemIcons = new ItemIconCache(itemManager, itemTable);
		this.offerIcons = new ItemIconCache(itemManager, offerTable);
		this.geLogPanel = new GeLogPanel(geEventStore, config);
		// Repaints the log panel itself once a sprite arrives.
		this.geLogIcons = new ItemIconCache(itemManager, geLogPanel);
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
	 * Right-click menu on an account row.
	 *
	 * <p>Selects the row under the cursor before showing the menu. Without
	 * that, right-clicking a row that is not already selected acts on whatever
	 * was selected before - which for a delete is the kind of mistake that is
	 * only noticed afterwards.
	 */
	/**
	 * Opens the bank screenshot dialog for the selected account.
	 *
	 * <p>Drawn from the stored snapshot, so it works for an account that is not
	 * logged in - which is the case this plugin exists for. An account whose
	 * bank has never been opened has nothing to draw, and says so rather than
	 * producing an empty grid that looks like an empty bank.
	 */
	private void screenshotSelectedBank(JTable table)
	{
		int viewRow = table.getSelectedRow();
		if (viewRow < 0)
		{
			return;
		}
		AccountRecord record = accountModel.recordAt(table.convertRowIndexToModel(viewRow));
		if (record == null)
		{
			return;
		}
		if (!record.hasBankSnapshot() || record.bankItems.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"This account's bank has never been opened, so there is nothing stored to draw.",
				"Screenshot bank", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		showBankScreenshotDialog(nameOf(record), record.bankItems,
			"bank last seen " + Format.relativeTime(record.lastSnapshotAt));
	}

	/**
	 * Screenshots every item held across the whole roster, not one bank.
	 *
	 * <p>Built from the same aggregate the All items tab shows, so the picture
	 * agrees with the table: one slot per distinct item, quantities summed
	 * across every visible account, priced at the most recently seen price.
	 *
	 * <p>Quantities are summed as longs and then clamped into the slot, because
	 * forty accounts of stacked runes can exceed what one in-game stack can
	 * hold - the total is real even where no single bank could contain it.
	 */
	/**
	 * Screenshots every tracked Grand Exchange slot as one image.
	 *
	 * <p>Empty slots are left out. They are not offers, and padding the picture
	 * with blank boxes would say nothing about what is actually on the market.
	 */
	private void screenshotOffers()
	{
		List<GeScreenshot.Entry> entries = new ArrayList<>();
		for (AccountRecord record : visibleAccounts)
		{
			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (offer != null && offer.isActive())
				{
					entries.add(new GeScreenshot.Entry(nameOf(record), offer));
				}
			}
		}
		if (entries.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"No account is holding an open Grand Exchange offer.",
				"Screenshot offers", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		showGeScreenshotDialog(entries);
	}

	private void showGeScreenshotDialog(List<GeScreenshot.Entry> entries)
	{
		JDialog dialog = new JDialog(this, "Grand Exchange screenshot", false);
		dialog.setLayout(new BorderLayout(0, 6));

		JComboBox<GeScreenshot.Side> sideBox = new JComboBox<>(GeScreenshot.Side.values());
		JCheckBox marketGapBox = new JCheckBox("Price vs market", true);
		marketGapBox.setToolTipText("Show how far each listing sits from the market price - green when the gap favours you, red when it does not");
		JComboBox<GeScreenshot.Sort> geSortBox =
			new JComboBox<>(GeScreenshot.Sort.values());

		JLabel preview = new JLabel();
		preview.setVerticalAlignment(SwingConstants.TOP);

		final Map<Integer, java.awt.Image> sprites = new HashMap<>();
		final Map<Integer, BufferedImage> frameSprites = new HashMap<>();
		final boolean[] redrawPending = {false};
		final BankScreenshot.Rendered[] current = {null};

		Runnable draw = () ->
		{
			GeScreenshot.Side side = (GeScreenshot.Side) sideBox.getSelectedItem();
			GeScreenshot.Sort geSort = (GeScreenshot.Sort) geSortBox.getSelectedItem();
			current[0] = GeScreenshot.render("Grand Exchange", entries,
				side == null ? GeScreenshot.Side.ALL : side,
				geSort == null ? GeScreenshot.Sort.PROGRESS_DESC : geSort,
				marketGapBox.isSelected(),
				id -> sprites.get(id), id -> frameSprites.get(id));
			preview.setIcon(new ImageIcon(current[0].image));
			dialog.pack();
		};

		if (spriteManager != null)
		{
			for (int id : BankScreenshot.framePieces())
			{
				final int spriteId = id;
				spriteManager.getSpriteAsync(spriteId, 0, img ->
					SwingUtilities.invokeLater(() ->
					{
						if (img != null)
						{
							frameSprites.put(spriteId, img);
							draw.run();
						}
					}));
			}
		}

		if (itemManager != null)
		{
			for (GeScreenshot.Entry entry : entries)
			{
				int id = entry.offer.itemId;
				if (id <= 0 || sprites.containsKey(id))
				{
					continue;
				}
				net.runelite.client.util.AsyncBufferedImage img =
					itemManager.getImage(id, entry.offer.totalQuantity, false);
				sprites.put(id, img);
				img.onLoaded(() ->
				{
					synchronized (redrawPending)
					{
						if (redrawPending[0])
						{
							return;
						}
						redrawPending[0] = true;
					}
					SwingUtilities.invokeLater(() ->
					{
						synchronized (redrawPending)
						{
							redrawPending[0] = false;
						}
						draw.run();
					});
				});
			}
		}

		ToolTipManager.sharedInstance().registerComponent(preview);
		preview.addMouseMotionListener(new java.awt.event.MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(java.awt.event.MouseEvent me)
			{
				BankScreenshot.Rendered r = current[0];
				if (r == null)
				{
					preview.setToolTipText(null);
					return;
				}
				// The label centres the image, so shift the pointer back into
				// image space before hit-testing a slot.
				int ox = Math.max(0, (preview.getWidth() - r.image.getWidth()) / 2);
				preview.setToolTipText(r.tooltipAt(me.getX() - ox, me.getY()));
			}
		});

		sideBox.addActionListener(e -> draw.run());
		geSortBox.addActionListener(e -> draw.run());
		marketGapBox.addActionListener(e -> draw.run());

		JButton save = new JButton("Save PNG...");
		save.addActionListener(e ->
			saveBankImage(current[0] == null ? null : current[0].image, "grand-exchange"));
		JButton copy = new JButton("Copy to clipboard");
		copy.addActionListener(e ->
		{
			if (current[0] != null)
			{
				Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new ImageTransferable(current[0].image), null);
			}
		});

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		controls.add(new JLabel("Show:"));
		controls.add(sideBox);
		controls.add(new JLabel("Sort:"));
		controls.add(geSortBox);
		controls.add(marketGapBox);
		controls.add(save);
		controls.add(copy);

		dialog.add(controls, BorderLayout.NORTH);
		dialog.add(new JScrollPane(preview), BorderLayout.CENTER);
		draw.run();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void screenshotAllItems()
	{
		List<ItemAggregator.ItemTotal> totals = ItemAggregator.aggregate(visibleAccounts);
		if (totals.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"No bank snapshots yet, so there is nothing to draw.",
				"Screenshot all items", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		List<BankItem> items = new ArrayList<>(totals.size());
		for (ItemAggregator.ItemTotal total : totals)
		{
			int quantity = (int) Math.min(Integer.MAX_VALUE, total.totalQuantity);
			// The members flag has to come along, or the membership filter has
			// nothing to test on the roster-wide export.
			items.add(new BankItem(total.itemId, quantity, total.name, total.unitPrice, 0,
				total.members));
		}

		showBankScreenshotDialog("All items",  items,
			"every item across " + visibleAccounts.size()
				+ (visibleAccounts.size() == 1 ? " account" : " accounts"));
	}

	private void showBankScreenshotDialog(String title, List<BankItem> bankItems,
		String subtitle)
	{
		JDialog dialog = new JDialog(this, "Bank screenshot - " + title, false);
		dialog.setLayout(new BorderLayout(0, 6));

		JComboBox<BankScreenshot.Sort> sortBox =
			new JComboBox<>(BankScreenshot.Sort.values());
		sortBox.setSelectedItem(BankScreenshot.Sort.VALUE);

		JComboBox<BankScreenshot.MinQuantity> minQtyBox =
			new JComboBox<>(BankScreenshot.MinQuantity.values());
		JComboBox<BankScreenshot.MinValue> minValBox =
			new JComboBox<>(BankScreenshot.MinValue.values());
		minQtyBox.setToolTipText("Hide small stacks - a high stack value still keeps one");
		minValBox.setToolTipText("Hide cheap stacks - a large quantity still keeps one");

		JComboBox<BankScreenshot.Membership> f2pBox =
			new JComboBox<>(BankScreenshot.Membership.values());
		f2pBox.setToolTipText("Members-only, free-to-play, or both. Items whose flag has not been captured show either way - refresh prices to fill them in");

		JComboBox<String> colsBox = new JComboBox<>(
			new String[]{"Auto width", "8 wide (game)", "12 wide", "16 wide", "20 wide", "24 wide"});
		colsBox.setToolTipText("Auto keeps the picture roughly landscape so it can be zoomed");

		JLabel preview = new JLabel();
		preview.setVerticalAlignment(SwingConstants.TOP);
		// A tooltip needs to appear over a slot rather than over the label, so
		// the delay is dropped and the text is recomputed on every move.
		ToolTipManager.sharedInstance().registerComponent(preview);
		preview.setHorizontalAlignment(SwingConstants.CENTER);

		// One cache for this dialog. Sprites fill themselves in after loading,
		// so a redraw is scheduled as they arrive rather than blocking on them.
		final Map<Integer, java.awt.Image> sprites = new HashMap<>();
		final Map<Integer, BufferedImage> frameSprites = new HashMap<>();
		final boolean[] redrawPending = {false};
		final BankScreenshot.Rendered[] current = {null};

		Runnable draw = () ->
		{
			BankScreenshot.Sort sort =
				(BankScreenshot.Sort) sortBox.getSelectedItem();
			BankScreenshot.MinQuantity minQ =
				(BankScreenshot.MinQuantity) minQtyBox.getSelectedItem();
			BankScreenshot.MinValue minV =
				(BankScreenshot.MinValue) minValBox.getSelectedItem();
			BankScreenshot.Membership mem =
				(BankScreenshot.Membership) f2pBox.getSelectedItem();
			int cols = colsBox.getSelectedIndex() == 0 ? 0
				: Integer.parseInt(String.valueOf(colsBox.getSelectedItem()).split(" ")[0]);
			current[0] = BankScreenshot.render(title, bankItems,
				sort == null ? BankScreenshot.Sort.VALUE : sort,
				id -> sprites.get(id), subtitle,
				minQ == null ? BankScreenshot.MinQuantity.ANY : minQ,
				minV == null ? BankScreenshot.MinValue.ANY : minV,
				mem == null ? BankScreenshot.Membership.ANY : mem, cols,
				id -> frameSprites.get(id));
			preview.setIcon(new ImageIcon(current[0].image));
			dialog.pack();
		};

		// The window frame is the game's own steel border, pulled from the
		// sprite cache. Each arrives on its own, so the preview simply redraws
		// as they land; a missing one falls back to a drawn bevel.
		if (spriteManager != null)
		{
			for (int id : BankScreenshot.framePieces())
			{
				final int spriteId = id;
				spriteManager.getSpriteAsync(spriteId, 0, img ->
					SwingUtilities.invokeLater(() ->
					{
						if (img != null)
						{
							frameSprites.put(spriteId, img);
							draw.run();
						}
					}));
			}
		}

		if (itemManager != null)
		{
			for (BankItem item : bankItems)
			{
				if (item.id <= 0 || sprites.containsKey(item.id))
				{
					continue;
				}
				// Quantity drives the stack variant, so coins render as the big
				// pile and arrows as the tall bundle rather than a single unit.
				// stackable=false suppresses RuneLite's own number: the quantity
				// is drawn separately in the game's stack colours.
				net.runelite.client.util.AsyncBufferedImage img =
					itemManager.getImage(item.id, item.quantity, false);
				sprites.put(item.id, img);
				img.onLoaded(() ->
				{
					// onLoaded fires on the client thread and once per sprite;
					// coalesce the burst into a single redraw on Swing.
					synchronized (redrawPending)
					{
						if (redrawPending[0])
						{
							return;
						}
						redrawPending[0] = true;
					}
					SwingUtilities.invokeLater(() ->
					{
						synchronized (redrawPending)
						{
							redrawPending[0] = false;
						}
						draw.run();
					});
				});
			}
		}

		preview.addMouseMotionListener(new java.awt.event.MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(java.awt.event.MouseEvent me)
			{
				BankScreenshot.Rendered r = current[0];
				if (r == null)
				{
					preview.setToolTipText(null);
					return;
				}
				// The label centres the image horizontally, so the pointer has to
				// be shifted back into image space before it can hit a slot.
				int ox = Math.max(0, (preview.getWidth() - r.image.getWidth()) / 2);
				preview.setToolTipText(r.tooltipAt(me.getX() - ox, me.getY()));
			}
		});

		sortBox.addActionListener(e -> draw.run());
		minQtyBox.addActionListener(e -> draw.run());
		minValBox.addActionListener(e -> draw.run());
		f2pBox.addActionListener(e -> draw.run());
		colsBox.addActionListener(e -> draw.run());

		JButton save = new JButton("Save PNG...");
		save.addActionListener(e -> saveBankImage(current[0] == null ? null : current[0].image, title));
		JButton copy = new JButton("Copy to clipboard");
		copy.addActionListener(e ->
		{
			if (current[0] != null)
			{
				Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new ImageTransferable(current[0].image), null);
			}
		});

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		controls.add(new JLabel("Sort:"));
		controls.add(sortBox);
		controls.add(new JLabel("Min stack:"));
		controls.add(minQtyBox);
		controls.add(new JLabel("Min value:"));
		controls.add(minValBox);
		controls.add(f2pBox);
		controls.add(colsBox);
		controls.add(save);
		controls.add(copy);

		dialog.add(controls, BorderLayout.NORTH);
		dialog.add(new JScrollPane(preview), BorderLayout.CENTER);
		draw.run();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private void saveBankImage(BufferedImage image, String title)
	{
		if (image == null)
		{
			return;
		}
		JFileChooser chooser = new JFileChooser();
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
			.format(new java.util.Date());
		chooser.setSelectedFile(new java.io.File(
			title.replaceAll("[^A-Za-z0-9._-]", "_") + "-bank-" + stamp + ".png"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		java.io.File target = chooser.getSelectedFile();
		if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".png"))
		{
			target = new java.io.File(target.getParentFile(), target.getName() + ".png");
		}
		try
		{
			javax.imageio.ImageIO.write(image, "png", target);
			JOptionPane.showMessageDialog(this, "Saved to:" + System.lineSeparator() + target,
				"Bank screenshot", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (java.io.IOException ex)
		{
			JOptionPane.showMessageDialog(this, "Could not write the image: " + ex.getMessage(),
				"Bank screenshot", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** Clipboard wrapper - Swing has no ready-made image transferable. */
	private static final class ImageTransferable implements java.awt.datatransfer.Transferable
	{
		private final java.awt.Image image;

		ImageTransferable(java.awt.Image image)
		{
			this.image = image;
		}

		@Override
		public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors()
		{
			return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor)
		{
			return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
			throws java.awt.datatransfer.UnsupportedFlavorException
		{
			if (!java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor))
			{
				throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
			}
			return image;
		}
	}

	private void attachAccountContextMenu(JTable table)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem editDisplay = new JMenuItem("Edit display name...");
		JMenuItem editLabel = new JMenuItem("Edit login label...");
		JMenuItem editCategory = new JMenuItem("Set group...");
		JMenuItem shot = new JMenuItem("Screenshot bank...");
		JMenuItem remove = new JMenuItem("Remove account...");

		menu.add(editDisplay);
		menu.add(editLabel);
		menu.add(editCategory);
		menu.addSeparator();
		menu.add(shot);
		menu.addSeparator();
		menu.add(remove);

		editDisplay.addActionListener(e -> editSelectedName(table, true));
		editLabel.addActionListener(e -> editSelectedName(table, false));
		editCategory.addActionListener(e -> editSelectedCategory(table));
		shot.addActionListener(e -> screenshotSelectedBank(table));
		remove.addActionListener(e -> confirmRemoveSelected(table));

		table.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				// Popup trigger fires on press on some platforms and release on
				// others, so both are handled.
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				if (!e.isPopupTrigger())
				{
					return;
				}
				int viewRow = table.rowAtPoint(e.getPoint());
				if (viewRow < 0)
				{
					return;
				}
				table.setRowSelectionInterval(viewRow, viewRow);
				menu.show(table, e.getX(), e.getY());
			}
		});
	}

	/** The account under the current selection, or null. */
	private AccountRecord selectedAccount(JTable table)
	{
		int viewRow = table.getSelectedRow();
		if (viewRow < 0)
		{
			return null;
		}
		return accountModel.recordAt(table.convertRowIndexToModel(viewRow));
	}

	/**
	 * @param displayName {@code true} to edit the shown name, {@code false}
	 *                    for the login label
	 */
	private void editSelectedName(JTable table, boolean displayName)
	{
		AccountRecord record = selectedAccount(table);
		if (record == null)
		{
			return;
		}

		String current = displayName ? record.displayName : record.loginLabel;
		String prompt = displayName
			? "Display name for this account." + "\n\n"
				+ "Overrides what the client reported. Clearing it lets the next"
				+ " login set it again."
			: "Login label - a nickname for which login this account sits under."
				+ "\n" + "Never a password.";

		String updated = (String) JOptionPane.showInputDialog(this, prompt,
			displayName ? "Edit display name" : "Edit login label",
			JOptionPane.PLAIN_MESSAGE, null, null, current);

		if (updated != null)
		{
			if (displayName)
			{
				store.updateDisplayName(record.accountHash, updated.trim());
			}
			else
			{
				store.updateLoginLabel(record.accountHash, updated.trim());
			}
			reload();
		}
	}

	private void editSelectedCategory(JTable table)
	{
		AccountRecord record = selectedAccount(table);
		if (record == null)
		{
			return;
		}

		JComboBox<String> input = new JComboBox<>(
			AccountCategory.presetLabels().toArray(new String[0]));
		input.setEditable(true);
		input.setSelectedItem(record.category == null ? "" : record.category);

		int result = JOptionPane.showConfirmDialog(this, input,
			"Group for " + nameOf(record), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION)
		{
			Object selected = input.getSelectedItem();
			store.updateCategory(record.accountHash, selected == null ? "" : selected.toString());
			reload();
		}
	}

	/**
	 * Two separate confirmations, not one strongly-worded dialog. A second
	 * dialog is a second deliberate click, which one "are you sure" does not
	 * force, and removal destroys every record of the account with no undo.
	 */
	private void confirmRemoveSelected(JTable table)
	{
		AccountRecord record = selectedAccount(table);
		if (record == null)
		{
			return;
		}

		String name = nameOf(record);
		int first = JOptionPane.showConfirmDialog(this,
			"Stop tracking " + name + "?" + "\n\n"
				+ "This deletes its bank snapshot, Grand Exchange offers and log,"
				+ "\n" + "skill history and wealth snapshots. There is no undo.",
			"Remove account", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

		if (first != JOptionPane.YES_OPTION)
		{
			return;
		}

		int second = JOptionPane.showConfirmDialog(this,
			"Really remove " + name + "? This cannot be undone.",
			"Confirm removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

		if (second == JOptionPane.YES_OPTION)
		{
			store.removeAccount(record.accountHash);
			historyStore.removeAccount(record.accountHash);
			geEventStore.removeAccount(record.accountHash);
			reload();
		}
	}

	/**
	 * Item id to current market price, built from data already priced.
	 *
	 * <p>Deliberately not an ItemManager lookup: that needs the client thread
	 * and this runs on the Swing thread. The aggregate totals were priced on
	 * the client thread during the last refresh, so reading them back here is
	 * both correct and free.
	 */
	private Map<Integer, Integer> currentMarketPrices()
	{
		Map<Integer, Integer> prices = new java.util.HashMap<>();
		for (ItemAggregator.ItemTotal total : currentTotals)
		{
			if (total.unitPrice > 0)
			{
				prices.put(total.itemId, total.unitPrice);
			}
		}
		// Offers carry their own captured market price, which covers items held
		// only in a sell offer and never seen in a bank.
		for (AccountRecord record : visibleAccounts)
		{
			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (offer.itemId > 0 && offer.marketPrice > 0)
				{
					prices.putIfAbsent(offer.itemId, offer.marketPrice);
				}
			}
		}
		return prices;
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

	/**
	 * One export control rather than a button per tab.
	 *
	 * <p>Six exports on six tabs would be six buttons to find; a single menu
	 * keeps them together and makes it obvious that the same set is always
	 * available. Every export honours the group filter and the name privacy
	 * setting, so a CSV says exactly what the window says.
	 */
	private JButton buildExportButton()
	{
		JButton button = new JButton("Export CSV");
		button.setToolTipText("Write the current view out as a spreadsheet");

		JPopupMenu menu = new JPopupMenu();
		addExport(menu, "Items across all accounts...", "items",
			f -> CsvExport.items(f, visibleAccounts));
		addExport(menu, "Accounts summary...", "accounts",
			f -> CsvExport.accounts(f, visibleAccounts, config.namePrivacy()));
		addExport(menu, "Every bank, one row per stack...", "banks",
			f -> CsvExport.banks(f, visibleAccounts, config.namePrivacy()));
		addExport(menu, "Grand Exchange offers...", "ge-offers",
			f -> CsvExport.offers(f, visibleAccounts, config.namePrivacy()));
		addExport(menu, "Wealth and XP history...", "history",
			f -> CsvExport.history(f, visibleAccounts, historyStore, config.namePrivacy()));
		addExport(menu, "Skills, one row per skill...", "skills",
			f -> CsvExport.skills(f, visibleAccounts, config.namePrivacy()));

		menu.addSeparator();
		JMenuItem html = new JMenuItem("Everything as one HTML page...");
		html.setToolTipText("One self-contained file with every table, "
			+ "sortable and filterable in a browser");
		html.addActionListener(e -> exportHtml());
		menu.add(html);

		JMenuItem everything = new JMenuItem("Everything as six CSV files...");
		everything.setToolTipText("Write every CSV export into one dated folder");
		everything.addActionListener(e -> exportEverything());
		menu.add(everything);

		button.addActionListener(e -> menu.show(button, 0, button.getHeight()));
		return button;
	}

	/**
	 * Writes the whole roster as a single self-contained HTML page.
	 *
	 * <p>One file rather than the folder of CSVs: it opens in a browser with
	 * nothing to unpack and can be handed to somebody who has neither the
	 * plugin nor a spreadsheet. The CSVs stay the right answer for computing
	 * on the data; this is the right answer for reading it.
	 */
	private void exportHtml()
	{
		if (visibleAccounts.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "No accounts are in view to export.",
				"Export", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		JFileChooser chooser = new JFileChooser();
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
			.format(new java.util.Date());
		chooser.setSelectedFile(new File("almanac-report-" + stamp + ".html"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		File target = chooser.getSelectedFile();
		if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".html"))
		{
			target = new File(target.getParentFile(), target.getName() + ".html");
		}

		try
		{
			int rows = HtmlReport.write(target, visibleAccounts, historyStore,
				config.namePrivacy());
			long kb = Math.max(1L, target.length() / 1024L);
			// Reports where the file went rather than offering to open it: plugins
			// may not launch files, and LinkBrowser.browse only takes http(s) links.
			JOptionPane.showMessageDialog(this,
				Format.exact(rows) + " rows written to:" + System.lineSeparator()
					+ target + System.lineSeparator()
					+ "(" + Format.exact(kb) + " KB)",
				"Export", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (IOException ex)
		{
			JOptionPane.showMessageDialog(this, "Could not write the file: " + ex.getMessage(),
				"Export", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Writes every export into one dated folder.
	 *
	 * <p>A folder of six files rather than one combined file, because the six
	 * have six different shapes - an item row and a snapshot row share no
	 * columns - and forcing them into one sheet would mean either a mostly
	 * empty grid or a key-value soup that nothing can pivot. Six tables is
	 * what the data actually is.
	 */
	private void exportEverything()
	{
		if (visibleAccounts.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "No accounts are in view to export.",
				"Export CSV", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Choose a folder to write the export into");
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
			.format(new java.util.Date());
		File dir = new File(chooser.getSelectedFile(), "almanac-export-" + stamp);
		if (!dir.mkdirs() && !dir.isDirectory())
		{
			JOptionPane.showMessageDialog(this, "Could not create:" + System.lineSeparator() + dir,
				"Export CSV", JOptionPane.ERROR_MESSAGE);
			return;
		}

		NamePrivacy privacy = config.namePrivacy();
		StringBuilder report = new StringBuilder();
		int total = 0;
		try
		{
			total += line(report, "items.csv",
				CsvExport.items(new File(dir, "items.csv"), visibleAccounts));
			total += line(report, "accounts.csv",
				CsvExport.accounts(new File(dir, "accounts.csv"), visibleAccounts, privacy));
			total += line(report, "banks.csv",
				CsvExport.banks(new File(dir, "banks.csv"), visibleAccounts, privacy));
			total += line(report, "ge-offers.csv",
				CsvExport.offers(new File(dir, "ge-offers.csv"), visibleAccounts, privacy));
			total += line(report, "history.csv",
				CsvExport.history(new File(dir, "history.csv"), visibleAccounts, historyStore, privacy));
			total += line(report, "skills.csv",
				CsvExport.skills(new File(dir, "skills.csv"), visibleAccounts, privacy));
		}
		catch (IOException ex)
		{
			// Whatever was written before the failure stays: a partial export is
			// more use than none, and the message says how far it got.
			JOptionPane.showMessageDialog(this,
				"Stopped after an error: " + ex.getMessage() + System.lineSeparator()
					+ System.lineSeparator() + report,
				"Export CSV", JOptionPane.ERROR_MESSAGE);
			return;
		}

		JOptionPane.showMessageDialog(this,
			Format.exact(total) + " rows across 6 files written to:" + System.lineSeparator()
				+ dir + System.lineSeparator() + System.lineSeparator() + report,
			"Export CSV", JOptionPane.INFORMATION_MESSAGE);
	}

	private static int line(StringBuilder report, String name, int rows)
	{
		report.append(name).append("  -  ").append(Format.exact(rows))
			.append(rows == 1 ? " row" : " rows").append(System.lineSeparator());
		return rows;
	}

	/** Writes a CSV, or reports why it could not. */
	private interface CsvWriter
	{
		int write(File file) throws IOException;
	}

	private void addExport(JPopupMenu menu, String label, String stem, CsvWriter writer)
	{
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(e ->
		{
			if (visibleAccounts.isEmpty())
			{
				JOptionPane.showMessageDialog(this, "No accounts are in view to export.",
					"Export CSV", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			File target = chooseCsvTarget(stem);
			if (target == null)
			{
				return;
			}
			try
			{
				int rows = writer.write(target);
				JOptionPane.showMessageDialog(this,
					Format.exact(rows) + (rows == 1 ? " row written to:" : " rows written to:")
						+ System.lineSeparator() + target,
					"Export CSV", JOptionPane.INFORMATION_MESSAGE);
			}
			catch (IOException ex)
			{
				JOptionPane.showMessageDialog(this, "Could not write the file: " + ex.getMessage(),
					"Export CSV", JOptionPane.ERROR_MESSAGE);
			}
		});
		menu.add(item);
	}

	/** Asks where to save, defaulting to a dated name, and ensures a .csv suffix. */
	private File chooseCsvTarget(String stem)
	{
		JFileChooser chooser = new JFileChooser();
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
			.format(new java.util.Date());
		chooser.setSelectedFile(new File("almanac-" + stem + "-" + stamp + ".csv"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return null;
		}
		File target = chooser.getSelectedFile();
		if (!target.getName().toLowerCase(Locale.ROOT).endsWith(".csv"))
		{
			target = new File(target.getParentFile(), target.getName() + ".csv");
		}
		return target;
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
		bar.add(buildExportButton());
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
			config.reminderAfterDays(), config.remindOnlyWithOffers());

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
		setRenderer(table, gpRenderer(), 9, 10, 11);
		setRenderer(table, questPointsRenderer(), 8);
		// Group (3) and Type (4) both name the account type, so both get the helm.
		setRenderer(table, accountTypeIconRenderer(), 3, 4);
		// Last login and days-since are the two columns the green/yellow/red
		// thresholds apply to, so both share one renderer.
		setRenderer(table, loginAgeRenderer(), AccountTableModel.COL_LAST_LOGIN,
			AccountTableModel.COL_DAYS);
		table.getColumnModel().getColumn(AccountTableModel.COL_LAST_LOGIN).setPreferredWidth(120);

		attachAccountContextMenu(table);

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
				? currentTotals.get(row).itemId : null,
			row -> row >= 0 && row < currentTotals.size()
				? currentTotals.get(row).totalQuantity : 1L), 0);
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

		searchField.setToolTipText(
			"Filter items by name, or type an item id to find one exactly");
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
		searchRow.add(Box.createHorizontalStrut(10));
		searchRow.add(new JLabel("Show:"));
		membershipFilter.setToolTipText(
			"Split the list by members-only and free-to-play items");
		membershipFilter.addActionListener(e -> applyFilter());
		searchRow.add(membershipFilter);
		JButton shotAll = new JButton("Screenshot all items...");
		shotAll.setToolTipText("Draw every item held across the roster as one bank image");
		shotAll.addActionListener(e -> screenshotAllItems());
		searchRow.add(Box.createHorizontalStrut(10));
		searchRow.add(shotAll);
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

		setRenderer(offerTable, gpRenderer(), 5, 7, 8);
		setRenderer(offerTable, marketGapRenderer(), 6);
		setRenderer(offerTable, progressRenderer(), 4);
		setRenderer(offerTable, new ItemCellRenderer(offerIcons,
			row -> offerModel.itemIdAt(row), offerModel::quantityAt), 3);
		offerTable.getColumnModel().getColumn(0).setPreferredWidth(130);
		offerTable.getColumnModel().getColumn(3).setPreferredWidth(190);
		offerTable.getColumnModel().getColumn(4).setPreferredWidth(140);

		showEmptySlots.setToolTipText(
			"Include the GE slots that are not holding an offer");
		showEmptySlots.addActionListener(e -> reload());

		offerSummaryLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JButton shotGe = new JButton("Screenshot offers...");
		shotGe.setToolTipText("Draw every tracked Grand Exchange slot as one image");
		shotGe.addActionListener(e -> screenshotOffers());

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		controls.add(showEmptySlots);
		controls.add(Box.createHorizontalStrut(12));
		controls.add(shotGe);
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

		snapshotExcludeGe.setToolTipText(
			"Show bank value alone, leaving out coins and stock committed to Grand Exchange "
				+ "offers - useful when offers are moving and you want the underlying trend");
		snapshotExcludeGe.addActionListener(e -> refreshSnapshots());

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		controls.add(new JLabel("Account:"));
		controls.add(snapshotAccountBox);
		controls.add(snapshotExcludeGe);

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
			snapshotModel.setCombined(histories, snapshotExcludeGe.isSelected());
			return;
		}

		AccountRecord selected = snapshotAccounts.get(index - 1);
		snapshotModel.setHistory(historyStore.historyFor(selected.accountHash),
			snapshotExcludeGe.isSelected());
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
		/**
		 * @param bankOnly leave Grand Exchange value out of the totals
		 */
		void setCombined(List<AccountHistory> histories, boolean bankOnly)
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
						row.total += bankOnly ? latest.bankValue : latest.totalWealth();
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

		void setHistory(AccountHistory history, boolean bankOnly)
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
					row.total = bankOnly ? snapshot.bankValue : snapshot.totalWealth();
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
	/**
	 * Counts a chosen set of items across every tracked account.
	 *
	 * <p>Backs both curiosity tables on the Interesting tab - the random event
	 * keepsakes and the burnt cooking failures. One model rather than two
	 * because the only thing that differs is which items qualify and whether
	 * a source column is worth showing.
	 *
	 * <p>Counts come from bank snapshots, so an account whose bank has never
	 * been opened contributes nothing. That is an unseen bank rather than an
	 * empty one, and the tab says so.
	 */
	private static class CuriosityTableModel extends AbstractTableModel
	{
		private static final String[] EVENT_COLUMNS = {"Item", "From", "Held", "Accounts"};
		private static final String[] BURNT_COLUMNS = {"Item", "Held", "Accounts"};

		private final boolean withSource;
		private final List<Object[]> rows = new ArrayList<>();
		/** Row index to account hash -> quantity, for the per-item pie. */
		private final List<Map<Long, Long>> rowSplits = new ArrayList<>();
		/** Item id per row, so the name cell can carry its sprite. */
		private final List<Integer> rowIds = new ArrayList<>();
		private long grandTotal;
		private int holdingAccounts;

		CuriosityTableModel(boolean withSource)
		{
			this.withSource = withSource;
		}

		private String[] columns()
		{
			return withSource ? EVENT_COLUMNS : BURNT_COLUMNS;
		}

		long grandTotal()
		{
			return grandTotal;
		}

		int distinctItems()
		{
			return rows.size();
		}

		int holdingAccounts()
		{
			return holdingAccounts;
		}

		/**
		 * Rebuilds from the supplied accounts.
		 *
		 * <p>Keyed by item id so a renamed item still aggregates onto one row,
		 * with the most recently seen display name winning. Ordered by total
		 * held descending, which is the order the question "what have I got a
		 * pile of" wants to be answered in.
		 */
		void setAccounts(List<AccountRecord> accounts, boolean randomEvents)
		{
			Map<Integer, long[]> totals = new java.util.LinkedHashMap<>();
			Map<Integer, Map<Long, Long>> splits = new java.util.LinkedHashMap<>();
			Map<Integer, String> names = new java.util.LinkedHashMap<>();
			java.util.Set<Long> seen = new java.util.HashSet<>();

			for (AccountRecord record : accounts)
			{
				boolean counted = false;
				for (BankItem item : record.bankItems)
				{
					boolean qualifies = randomEvents
						? RandomEventItems.isRandomEventItem(item.id)
						: RandomEventItems.isBurnt(item.name);
					if (!qualifies)
					{
						continue;
					}
					long[] cell = totals.computeIfAbsent(item.id, k -> new long[2]);
					cell[0] += item.quantity;
					splits.computeIfAbsent(item.id, k -> new java.util.LinkedHashMap<>())
						.merge(record.accountHash, (long) item.quantity, Long::sum);
					cell[1]++;
					names.put(item.id, item.name);
					counted = true;
				}
				if (counted)
				{
					seen.add(record.accountHash);
				}
			}

			rows.clear();
			rowSplits.clear();
			rowIds.clear();
			grandTotal = 0L;
			holdingAccounts = seen.size();

			List<Map.Entry<Integer, long[]>> ordered = new ArrayList<>(totals.entrySet());
			ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));

			for (Map.Entry<Integer, long[]> entry : ordered)
			{
				int id = entry.getKey();
				long held = entry.getValue()[0];
				long holders = entry.getValue()[1];
				grandTotal += held;
				String name = names.getOrDefault(id, "Item " + id);
				rows.add(withSource
					? new Object[]{name, RandomEventItems.sourceOf(id), held, (int) holders}
					: new Object[]{name, held, (int) holders});
				rowSplits.add(splits.getOrDefault(id, java.util.Collections.emptyMap()));
				rowIds.add(id);
			}
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
			return columns().length;
		}

		@Override
		public String getColumnName(int column)
		{
			return columns()[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			int held = withSource ? 2 : 1;
			if (column == held)
			{
				return Long.class;
			}
			return column == held + 1 ? Integer.class : String.class;
		}

		Integer itemIdAt(int modelRow)
		{
			return modelRow >= 0 && modelRow < rowIds.size() ? rowIds.get(modelRow) : null;
		}

		/** Account hash to quantity for one row, for the pie. */
		Map<Long, Long> splitAt(int modelRow)
		{
			return modelRow >= 0 && modelRow < rowSplits.size()
				? rowSplits.get(modelRow) : java.util.Collections.emptyMap();
		}

		String nameAt(int modelRow)
		{
			return modelRow >= 0 && modelRow < rows.size() ? String.valueOf(rows.get(modelRow)[0]) : "";
		}

		long heldAt(int modelRow)
		{
			if (modelRow < 0 || modelRow >= rows.size())
			{
				return 0L;
			}
			Object v = rows.get(modelRow)[withSource ? 2 : 1];
			return v instanceof Number ? ((Number) v).longValue() : 0L;
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			return rows.get(row)[column];
		}
	}

	private JPanel buildInterestingTab()
	{
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		JPanel stats = new JPanel();
		stats.setLayout(new BoxLayout(stats, BoxLayout.Y_AXIS));
		stats.setOpaque(false);

		for (JLabel label : new JLabel[] {
			totalXpLabel, totalLevelSumLabel, bondsLabel, playtimeLabel, highAlchLabel,
			riserLabel, dropperLabel,
			topTotalLevelLabel, topCombatLabel,
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
		// Column 4 is the top account's XP and was being left raw, so it read
		// as 40226501 beside a formatted 225,278,223 in the column before it.
		setRenderer(skillTable, countRenderer(), 1, 2, 4);
		// Highest total XP first - that ordering is the whole point of a
		// cross-account skill leaderboard.
		skillTable.getRowSorter().toggleSortOrder(2);
		skillTable.getRowSorter().toggleSortOrder(2);

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(stats, BorderLayout.NORTH);

		JTabbedPane curiosities = new JTabbedPane();
		curiosities.addTab("Skill XP", new JScrollPane(skillTable));
		curiosities.addTab("Random event items", buildCuriosityPanel(
			randomEventModel, randomEventSummary, true));
		curiosities.addTab("Burnt", buildCuriosityPanel(
			burntModel, burntSummary, false));

		wrapper.add(top, BorderLayout.NORTH);
		wrapper.add(curiosities, BorderLayout.CENTER);
		return wrapper;
	}

	/**
	 * One curiosity table with a summary line above it.
	 *
	 * <p>The summary carries the totals rather than a footer row, so sorting
	 * the table cannot move it or hide it under a scroll.
	 */
	private JPanel buildCuriosityPanel(CuriosityTableModel model, JLabel summary, boolean withSource)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		summary.setForeground(ColorScheme.BRAND_ORANGE);
		summary.setFont(FontManager.getRunescapeBoldFont());
		summary.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		JTable table = new JTable(model);
		table.setAutoCreateRowSorter(true);
		// Tall enough for a sprite, matching the All items table.
		table.setRowHeight(ICON_ROW_HEIGHT);
		// Its own icon cache: the cache repaints the component it was built
		// for as sprites arrive, so the two curiosity tables cannot share one.
		setRenderer(table, new ItemCellRenderer(new ItemIconCache(itemManager, table),
			model::itemIdAt, model::heldAt), 0);
		table.getColumnModel().getColumn(0).setPreferredWidth(200);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setToolTipText("Select a row to see which accounts hold it");
		int held = withSource ? 2 : 1;
		setRenderer(table, countRenderer(), held, held + 1);
		// Biggest pile first - the whole point of the table.
		table.getRowSorter().toggleSortOrder(held);
		table.getRowSorter().toggleSortOrder(held);

		JLabel splitTitle = new JLabel("Select a row");
		splitTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		splitTitle.setFont(FontManager.getRunescapeBoldFont());
		splitTitle.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 0));

		PieChartPanel chart = new PieChartPanel(false);
		chart.setEmptyMessage("Select a row to see which accounts hold it");

		JPanel right = new JPanel(new BorderLayout());
		right.setOpaque(false);
		right.add(splitTitle, BorderLayout.NORTH);
		right.add(chart, BorderLayout.CENTER);

		// Selection drives the pie, so a double-click works as well as a single
		// one - a double-click selects the row on its way down.
		table.getSelectionModel().addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				updateCuriositySplit(table, model, splitTitle, chart);
			}
		});

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
			new JScrollPane(table), right);
		split.setResizeWeight(0.62);
		split.setBorder(null);

		panel.add(summary, BorderLayout.NORTH);
		panel.add(split, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Repaints the per-item pie beside a curiosity table.
	 *
	 * <p>Split by quantity, not value: a burnt shark is worthless, so splitting
	 * one by GE price would render every wedge as zero. "Who has the pile" is
	 * the question these tables are for.
	 */
	private void updateCuriositySplit(JTable table, CuriosityTableModel model,
		JLabel title, PieChartPanel chart)
	{
		int viewRow = table.getSelectedRow();
		if (viewRow < 0)
		{
			title.setText("Select a row");
			chart.setSlices(new ArrayList<>(), config.maxChartSlices());
			return;
		}

		int modelRow;
		try
		{
			modelRow = table.convertRowIndexToModel(viewRow);
		}
		catch (IndexOutOfBoundsException e)
		{
			// Row vanished between sort and paint.
			return;
		}

		Map<Long, Long> split = model.splitAt(modelRow);
		Map<Long, String> labels = plugin.accountLabels();
		List<ItemAggregator.Slice> slices = new ArrayList<>();
		for (Map.Entry<Long, Long> entry : split.entrySet())
		{
			slices.add(new ItemAggregator.Slice(
				labels.getOrDefault(entry.getKey(), "Account " + Long.toHexString(entry.getKey())),
				entry.getValue(), entry.getKey()));
		}
		slices.sort((a, b) -> Long.compare(b.value, a.value));

		title.setText(String.format(Locale.ROOT, "%s - %s across %d account%s",
			model.nameAt(modelRow), Format.exact(model.heldAt(modelRow)),
			split.size(), split.size() == 1 ? "" : "s"));
		chart.setSlices(slices, config.maxChartSlices());
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
		updateMoverLabels();
		updateHighAlchLabel(accounts);
		updatePlaytimeLabel(accounts);

		dataCompletenessLabel.setText(neverOpened == 0
			? "Every tracked account has had its bank opened at least once"
			: neverOpened + " of " + accounts.size() + " tracked accounts have never had their bank opened");

		skillTotalsModel.setAccounts(accounts, config.namePrivacy());

		randomEventModel.setAccounts(accounts, true);
		burntModel.setAccounts(accounts, false);
		updateCuriositySummaries(accounts);
	}

	/**
	 * Summary lines above the two curiosity tables.
	 *
	 * <p>Both distinguish "nothing held" from "nothing seen": an account whose
	 * bank has never been opened cannot contribute, and saying "none" when the
	 * truth is "not looked yet" would be wrong.
	 */
	private void updateCuriositySummaries(List<AccountRecord> accounts)
	{
		int withBank = 0;
		for (AccountRecord record : accounts)
		{
			if (record.hasBankSnapshot())
			{
				withBank++;
			}
		}

		if (withBank == 0)
		{
			String none = "No bank has been opened yet - open one to count these";
			randomEventSummary.setText(none);
			burntSummary.setText(none);
			return;
		}

		randomEventSummary.setText(String.format(Locale.ROOT,
			"%s random event item%s across %d of %d account%s   (%d of %d reward items seen)",
			Format.exact(randomEventModel.grandTotal()),
			randomEventModel.grandTotal() == 1L ? "" : "s",
			randomEventModel.holdingAccounts(), withBank,
			withBank == 1 ? "" : "s",
			randomEventModel.distinctItems(), RandomEventItems.trackedCount()));
		randomEventSummary.setToolTipText(
			"Keepsakes from the random events - frog, lederhosen, mime, camo, "
				+ "zombie, beekeeper and the rest. Counted from bank snapshots.");

		burntSummary.setText(String.format(Locale.ROOT,
			"%s burnt item%s across %d of %d account%s   (%d kind%s)",
			Format.exact(burntModel.grandTotal()),
			burntModel.grandTotal() == 1L ? "" : "s",
			burntModel.holdingAccounts(), withBank,
			withBank == 1 ? "" : "s",
			burntModel.distinctItems(),
			burntModel.distinctItems() == 1 ? "" : "s"));
		burntSummary.setToolTipText(
			"Everything the game calls \"Burnt something\" - a running tally of "
				+ "failed cooking still sitting in the banks.");
	}

	/**
	 * Combined time played across the roster, in hours.
	 *
	 * <p>Read from the game's own Account Summary value, so it is the
	 * account's real lifetime rather than time spent in this client. Only
	 * accounts that have logged in since this started being captured
	 * contribute, which is stated rather than quietly folded in.
	 */
	private void updatePlaytimeLabel(List<AccountRecord> accounts)
	{
		long minutes = 0L;
		int known = 0;
		AccountRecord most = null;

		for (AccountRecord record : accounts)
		{
			if (record.playtimeMinutes <= 0)
			{
				continue;
			}
			minutes += record.playtimeMinutes;
			known++;
			if (most == null || record.playtimeMinutes > most.playtimeMinutes)
			{
				most = record;
			}
		}

		if (known == 0)
		{
			playtimeLabel.setText("Time played: not captured yet - log into an account to record it");
			playtimeLabel.setToolTipText(null);
			return;
		}

		long hours = minutes / 60L;
		playtimeLabel.setText(String.format(Locale.ROOT,
			"Time played: %s hours across %d of %d accounts   (most: %s, %s hours)",
			Format.exact(hours), known, accounts.size(),
			nameOf(most), Format.exact(most.playtimeMinutes / 60L)));
		playtimeLabel.setToolTipText(known < accounts.size()
			? (accounts.size() - known) + " accounts have not logged in since this started being recorded"
			: "Every account has reported its time played");
	}

	/**
	 * Combined high alchemy value of everything held.
	 *
	 * <p>Reported next to the market value rather than instead of it, because
	 * the interesting figure is the gap: when alch value exceeds market value
	 * the item is worth alching rather than selling, and across a large bank
	 * that is not obvious item by item.
	 *
	 * <p>Only bank contents count. Stock in a sell offer is already committed
	 * to being sold, so its alch value is not a choice available to you.
	 */
	private void updateHighAlchLabel(List<AccountRecord> accounts)
	{
		if (!config.showHighAlch())
		{
			highAlchLabel.setText("");
			highAlchLabel.setToolTipText(null);
			return;
		}

		long alch = 0L;
		long market = 0L;
		int unpriced = 0;
		for (AccountRecord record : accounts)
		{
			for (BankItem item : record.bankItems)
			{
				if (item.quantity <= 0)
				{
					continue;
				}
				if (item.haPrice <= 0)
				{
					// Captured before alch values were recorded, or genuinely
					// unalchable. Counted so the figure can be qualified rather
					// than quietly understating.
					unpriced++;
					continue;
				}
				alch += item.totalHaValue();
				market += item.totalValue();
			}
		}

		if (alch == 0L)
		{
			highAlchLabel.setText("High alch value: not captured yet - reopen a bank to record it");
			highAlchLabel.setToolTipText(null);
			return;
		}

		long difference = alch - market;
		highAlchLabel.setText(String.format(Locale.ROOT,
			"High alch value of banked items: %s   (market %s, %s %s by alching)",
			Format.gp(alch), Format.gp(market),
			difference >= 0 ? "+" + Format.gpBare(difference) : Format.gpBare(difference),
			difference >= 0 ? "gained" : "lost"));
		highAlchLabel.setToolTipText(unpriced > 0
			? unpriced + " item stacks have no alch value recorded and are left out"
			: "Every banked stack has an alch value recorded");
	}

	/**
	 * The item whose price has moved most since its recorded baseline.
	 *
	 * <p>Restricted to stacks of more than one. A single unique piece of gear
	 * swinging in price is not as meaningful as a stack you could actually
	 * choose to sell into a moving market, and it is the stack's value that
	 * moves your net worth.
	 */
	private void updateMoverLabels()
	{
		ItemAggregator.ItemTotal riser = null;
		ItemAggregator.ItemTotal dropper = null;
		double bestUp = 0.0;
		double bestDown = 0.0;
		long riserAt = 0L;
		long dropperAt = 0L;

		for (ItemAggregator.ItemTotal total : currentTotals)
		{
			if (total.totalQuantity <= 1L || total.unitPrice <= 0)
			{
				continue;
			}
			PricePoint baseline = historyStore.priceBaseline(total.itemId);
			if (baseline == null || baseline.price <= 0 || baseline.price == total.unitPrice)
			{
				continue;
			}

			double pct = 100.0 * (total.unitPrice - baseline.price) / (double) baseline.price;
			if (pct > bestUp)
			{
				bestUp = pct;
				riser = total;
				riserAt = baseline.at;
			}
			else if (pct < bestDown)
			{
				bestDown = pct;
				dropper = total;
				dropperAt = baseline.at;
			}
		}

		riserLabel.setText(describeMover("Biggest riser", riser, bestUp, riserAt));
		riserLabel.setForeground(riser == null ? ColorScheme.LIGHT_GRAY_COLOR : config.gainColour());
		dropperLabel.setText(describeMover("Biggest dropper", dropper, bestDown, dropperAt));
		dropperLabel.setForeground(dropper == null ? ColorScheme.LIGHT_GRAY_COLOR : config.lossColour());
	}

	private String describeMover(String prefix, ItemAggregator.ItemTotal total, double pct, long since)
	{
		if (total == null)
		{
			return prefix + ": nothing has moved yet - prices are compared against a baseline "
				+ "the plugin records itself, which fills in as prices refresh";
		}
		return String.format(Locale.ROOT,
			"%s: %s %s%.1f%% since %s  -  your %s stack is worth %s",
			prefix, total.name, pct >= 0 ? "+" : "", pct,
			LoginAge.exact(since), Format.quantity(total.totalQuantity),
			Format.gp(total.totalValue));
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

	/** Rows matching the members/F2P selector alone. */
	private RowFilter<ItemTableModel, Integer> membershipOnlyFilter(int membership)
	{
		return withMembership((RowFilter<ItemTableModel, Integer>) null, membership);
	}

	/**
	 * Wraps a filter so the members/F2P selector applies on top of it.
	 *
	 * <p>An item whose flag has not been captured yet shows under both. That
	 * is not evidence of either answer, and dropping it would understate both
	 * sides; a price refresh fills the flag in.
	 */
	private RowFilter<ItemTableModel, Integer> withMembership(
		final RowFilter<? super ItemTableModel, ? super Integer> inner, final int membership)
	{
		return new RowFilter<ItemTableModel, Integer>()
		{
			@Override
			public boolean include(Entry<? extends ItemTableModel, ? extends Integer> entry)
			{
				if (inner != null && !inner.include(entry))
				{
					return false;
				}
				if (membership == 0)
				{
					return true;
				}
				int row = entry.getIdentifier();
				if (row < 0 || row >= currentTotals.size())
				{
					return false;
				}
				Boolean members = currentTotals.get(row).members;
				if (members == null)
				{
					return true;
				}
				return membership == 2 ? members : !members;
			}
		};
	}

	private void applyFilter()
	{
		String text = searchField.getText();
		// Combined with the membership selector below: a JTable takes one row
		// filter, so setting a second would silently replace the first.
		final int membership = membershipFilter.getSelectedIndex();
		if (text == null || text.trim().isEmpty())
		{
			itemSorter.setRowFilter(membership == 0 ? null : membershipOnlyFilter(membership));
			return;
		}

		// An all-digits search is treated as an item id. Names never consist
		// only of digits, so this cannot shadow a real name search, and it is
		// the only way to pin down one of several similarly-named variants.
		String trimmed = text.trim();
		if (trimmed.matches("\\d+"))
		{
			final int wantedId = Integer.parseInt(trimmed);
			RowFilter<ItemTableModel, Integer> byId = new RowFilter<ItemTableModel, Integer>()
			{
				@Override
				public boolean include(Entry<? extends ItemTableModel, ? extends Integer> entry)
				{
					int row = entry.getIdentifier();
					return row >= 0 && row < currentTotals.size()
						&& currentTotals.get(row).itemId == wantedId;
				}
			};
			itemSorter.setRowFilter(withMembership(byId, membership));
			return;
		}
		// Quoted so a stray '(' or '*' in the search box is treated as text
		// rather than blowing up as a malformed regex.
		itemSorter.setRowFilter(withMembership(RowFilter.regexFilter(
			"(?i)" + Pattern.quote(text.trim()), 0), membership));
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

		wealthChart.setSlices(
			ItemAggregator.wealthByAccount(accounts, this::nameOf), config.maxChartSlices());

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
		geLogPanel.setMarketPrices(currentMarketPrices());
		geLogPanel.setItemIcons(geLogIcons);
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
	 * Offer progress, as a filled bar or as a fraction.
	 *
	 * <p>The bar is painted rather than using a JProgressBar, because a
	 * progress bar component inside a table cell brings its own look-and-feel
	 * borders and does not follow the plugin's theme. A filled rectangle is
	 * two calls and always matches.
	 */
	/**
	 * How far a listing sits from the market price.
	 *
	 * <p>Green when the gap favours the account, red when it does not - and
	 * which way round that is depends on the side of the book, so the offer
	 * itself is asked rather than the sign of the number. An item whose market
	 * price has never been captured shows a dash, because a nil difference and
	 * an unknown one are not the same claim.
	 */
	private DefaultTableCellRenderer marketGapRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				setHorizontalAlignment(SwingConstants.RIGHT);

				GrandExchangeRecord offer = null;
				try
				{
					offer = offerModel.offerAt(t.convertRowIndexToModel(row));
				}
				catch (IndexOutOfBoundsException e)
				{
					// Row vanished between sort and paint.
				}

				if (offer == null || offer.priceVsMarket() == null)
				{
					setText("-");
					setToolTipText(offer == null || !offer.isActive()
						? null : "Market price for this item has not been captured yet");
					return this;
				}

				setText(GeScreenshot.marketGapLabel(offer));
				setForeground(offer.priceGapFavourable()
					? config.gainColour() : config.lossColour());
				setToolTipText(offer.isBuy()
					? "Buying at " + Format.exact(offer.pricePerItem)
						+ " against a market price of " + Format.exact(offer.marketPrice)
					: "Selling at " + Format.exact(offer.pricePerItem)
						+ " against a market price of " + Format.exact(offer.marketPrice));
				return this;
			}
		};
	}

	private DefaultTableCellRenderer progressRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			private double fraction = -1.0;
			private String text = "-";

			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				fraction = value instanceof Number ? ((Number) value).doubleValue() : -1.0;

				GrandExchangeRecord offer = null;
				try
				{
					offer = offerModel.offerAt(t.convertRowIndexToModel(row));
				}
				catch (IndexOutOfBoundsException e)
				{
					// Row vanished between sort and paint.
				}

				if (fraction < 0.0 || offer == null)
				{
					text = "-";
					setToolTipText(null);
				}
				else
				{
					text = Format.exact(offer.quantitySold) + " / " + Format.exact(offer.totalQuantity)
						+ "  (" + Math.round(fraction * 100) + "%)";
					setToolTipText(text);
				}

				setText(config.geProgressBar() ? "" : text);
				setHorizontalAlignment(SwingConstants.CENTER);
				return this;
			}

			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				super.paintComponent(g);
				if (!config.geProgressBar() || fraction < 0.0)
				{
					return;
				}

				int pad = 3;
				int w = getWidth() - pad * 2;
				int h = getHeight() - pad * 2;
				if (w <= 0 || h <= 0)
				{
					return;
				}

				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				try
				{
					g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
					g2.fillRect(pad, pad, w, h);
					// Banded by progress - see ProgressColours. Green is reserved
					// for actually complete, so a nearly-full bar still reads as
					// unfinished.
					g2.setColor(config.geProgressBarColours()
						? ProgressColours.forFraction(fraction)
						: (fraction >= 1.0 ? config.gainColour() : ColorScheme.BRAND_ORANGE));
					g2.fillRect(pad, pad, (int) Math.round(w * Math.min(1.0, fraction)), h);

					g2.setColor(config.geProgressBarColours()
						? ProgressColours.textOn(fraction) : getForeground());
					java.awt.FontMetrics fm = g2.getFontMetrics();
					String label = Math.round(fraction * 100) + "%";
					g2.drawString(label,
						pad + (w - fm.stringWidth(label)) / 2,
						pad + (h + fm.getAscent()) / 2 - 2);
				}
				finally
				{
					g2.dispose();
				}
			}
		};
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
	private DefaultTableCellRenderer gpRenderer()
	{
		return gpRenderer(null);
	}

	/**
	 * @param foreground fixed colour, or {@code null} to colour by the game's
	 *                   stack scale
	 */
	private DefaultTableCellRenderer gpRenderer(Color foreground)
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
						setForeground(foreground != null ? foreground
							: StackFormat.colour(amount, config.viewerTheme().isDark()));
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
	private DefaultTableCellRenderer countRenderer()
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
						setForeground(StackFormat.colour(amount, config.viewerTheme().isDark()));
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
	/**
	 * Quest points as {@code earned/available}.
	 *
	 * <p>The cell value is the score alone so the column sorts numerically.
	 * The denominator is the account's own recorded maximum, read back from
	 * the record rather than assumed, so an account captured before a quest
	 * release still shows the total that applied when it was read.
	 */
	private DefaultTableCellRenderer questPointsRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				setHorizontalAlignment(RIGHT);

				AccountRecord record = null;
				try
				{
					record = accountModel.recordAt(t.convertRowIndexToModel(row));
				}
				catch (IndexOutOfBoundsException e)
				{
					// Row vanished between sort and paint.
				}

				if (record == null || !record.hasQuestPoints())
				{
					setText("-");
					setToolTipText("Not captured yet - log into this account to record it");
					return this;
				}

				setText(record.questPointsLabel());
				setToolTipText(record.questPoints == record.questPointsMax
					? "Every quest complete"
					: (record.questPointsMax - record.questPoints) + " quest points remaining");
				return this;
			}
		};
	}

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

				// Two columns share this renderer and they hold different things:
				// the date column holds a timestamp to format, the days column a
				// plain count. Told apart by which column is being painted
				// rather than by guessing from the value's magnitude.
				boolean isDateColumn = t.convertColumnIndexToModel(column)
					== AccountTableModel.COL_LAST_LOGIN;
				if (value instanceof Number)
				{
					long shown = ((Number) value).longValue();
					if (isDateColumn)
					{
						setText(LoginAge.exact(shown));
						setHorizontalAlignment(SwingConstants.LEFT);
					}
					else
					{
						setText(shown < 0L ? "-" : Long.toString(shown));
						setHorizontalAlignment(SwingConstants.RIGHT);
					}
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
						? LoginAge.iso(record.lastLoginAt)
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
				// Centred so the helm sits under the column heading rather than
				// hard against the left edge with the text trailing it.
				setHorizontalAlignment(SwingConstants.CENTER);
				setHorizontalTextPosition(SwingConstants.RIGHT);
				setIconTextGap(4);

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
	private DefaultTableCellRenderer quantityRenderer()
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
						setForeground(StackFormat.colour(amount, config.viewerTheme().isDark()));
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

	/** Resolves how many that row holds, so the sprite shows the right stack. */
	private interface QuantityLookup
	{
		long quantityAt(int modelRow);
	}

	/** Item name cell with its sprite alongside. */
	private static class ItemCellRenderer extends DefaultTableCellRenderer
	{
		private final ItemIconCache icons;
		private final IdLookup lookup;
		private final QuantityLookup quantities;

		ItemCellRenderer(ItemIconCache icons, IdLookup lookup)
		{
			this(icons, lookup, row -> 1L);
		}

		ItemCellRenderer(ItemIconCache icons, IdLookup lookup, QuantityLookup quantities)
		{
			this.icons = icons;
			this.lookup = lookup;
			this.quantities = quantities;
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value,
			boolean selected, boolean focused, int row, int column)
		{
			super.getTableCellRendererComponent(table, value, selected, focused, row, column);
			Integer id = null;
			long quantity = 1L;
			try
			{
				int modelRow = table.convertRowIndexToModel(row);
				id = lookup.idAt(modelRow);
				quantity = quantities.quantityAt(modelRow);
			}
			catch (IndexOutOfBoundsException e)
			{
				// Row vanished between sort and paint; render without a sprite.
			}
			setIcon(id == null ? null : icons.get(id, quantity));
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
			"Unit price", "vs market", "Spent", "Committed", "Status"
		};

		private List<OfferRow> rows = new ArrayList<>();

		void setRows(List<OfferRow> rows)
		{
			this.rows = rows;
			fireTableDataChanged();
		}

		GrandExchangeRecord offerAt(int modelRow)
		{
			return modelRow < 0 || modelRow >= rows.size() ? null : rows.get(modelRow).offer;
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

		/** Offer size, so the name cell's sprite shows the right stack. */
		long quantityAt(int modelRow)
		{
			GrandExchangeRecord offer = offerAt(modelRow);
			return offer == null ? 1L : Math.max(1L, offer.totalQuantity);
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
				case 4:
				case 6:
					return Double.class;
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
					// The raw fraction, so the renderer can draw either a bar or
					// the text form from the same value, and so the column sorts
					// by how full an offer is rather than alphabetically.
					return offer.isActive() ? offer.progress() : -1.0;
				case 5:
					return (long) offer.pricePerItem;
				case 6:
				{
					// The raw fraction, so the column sorts by how far the listing
					// sits from market rather than by the formatted string. Null
					// becomes NaN, which sorts to one end and renders as a dash -
					// an unpriced item must not read as a nil difference.
					Double gap = offer.isActive() ? offer.priceVsMarket() : null;
					return gap == null ? Double.NaN : gap;
				}
				case 7:
					return offer.spent;
				case 8:
					return offer.committedValue();
				case 9:
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
			"Combat", "Total lvl", "Quests", "Bank", "GE", "Total",
			"Last login", "Days", "Bank last seen"
		};

		/** Column indexes other code needs to address by name rather than number. */
		static final int COL_LAST_LOGIN = 12;
		static final int COL_DAYS = 13;

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
				// Quests holds the score alone so the sorter orders it numerically;
				// the renderer is what appends the maximum.
				case 6:
				case 7:
				case 8:
				case 13:
					return Integer.class;
				case 9:
				case 10:
				case 11:
				case 12:
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
					// Score only. An account that has never been read returns 0
					// here and sorts to the bottom; the renderer shows it as a
					// dash rather than a misleading 0/0.
					return record.questPoints;
				case 9:
					return record.bankValue;
				case 10:
					return record.geValue();
				case 11:
					return includeGe ? record.totalWealth() : record.bankValue;
				case 12:
					// The raw timestamp, not the formatted string. The column
					// used to hold text and relied on yyyy-MM-dd sorting
					// lexically, which silently stopped being true the moment
					// the date format became configurable - SEP sorts before
					// OCT alphabetically but after it in a year. The renderer
					// formats it; the sorter sees a number and is always right.
					return record.lastLoginAt;
				case 13:
				{
					long days = LoginAge.daysSince(record.lastLoginAt, now);
					return days < 0L ? -1 : (int) Math.min(Integer.MAX_VALUE, days);
				}
				case 14:
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
