package com.accountalmanac;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import net.runelite.client.ui.ColorScheme;

/**
 * The Grand Exchange log: everything that happened to an offer, plus the
 * purchase and sale histories derived from it.
 *
 * <p>All three views read the same {@link GeEventStore}. Purchases and sales
 * are not separately recorded - they are the buy-side and sell-side outcome
 * events filtered out of the one log, which keeps a single source of truth and
 * means a trade cannot appear in history without its matching log entry.
 *
 * <p>Cancelled offers that partially filled appear in history too. A buy that
 * filled 400 of 1000 before being cancelled really did purchase 400 items, and
 * omitting it would leave a gap in the record of what was actually paid.
 */
class GeLogPanel extends JPanel
{
	private static final String ALL_ACCOUNTS = "All accounts";

	private final GeEventStore geEventStore;
	private final AccountAlmanacConfig config;

	/**
	 * Item sprites for the log's item column.
	 *
	 * <p>Created by the caller, which holds the ItemManager needed to fetch
	 * them. Null-safe: without one the column falls back to names alone.
	 */
	private ItemIconCache itemIcons;

	/**
	 * Resolves the name to show for an event's account.
	 *
	 * <p>Events store the account label as text captured when they were logged,
	 * so masking cannot be applied from the event alone - the owning account has
	 * to be looked up. Injected by the frame, which is what holds the roster and
	 * the privacy setting. Defaults to the stored label so the panel still works
	 * standalone.
	 */
	private java.util.function.Function<GeEvent, String> labelResolver = e -> e.accountLabel;

	/**
	 * Item id to current market price.
	 *
	 * <p>Supplied as a finished map rather than looked up on demand. A price
	 * lookup needs the client thread, and this renders on the Swing thread -
	 * so the caller resolves them once, from data already priced, and hands
	 * the result over. An item missing from the map simply shows no comparison.
	 */
	private Map<Integer, Integer> marketPrices = java.util.Collections.emptyMap();

	private final EventTableModel allModel = new EventTableModel();
	private final TradeTableModel purchaseModel = new TradeTableModel(false);
	private final TradeTableModel saleModel = new TradeTableModel(true);
	private final CostBasisTableModel costBasisModel = new CostBasisTableModel();

	private final JComboBox<String> accountBox = new JComboBox<>();
	private final JCheckBox hideCollections = new JCheckBox("Hide collections");
	private final JLabel summaryLabel = new JLabel();

	private boolean populating;
	private List<GeEvent> events = new ArrayList<>();

	GeLogPanel(GeEventStore geEventStore, AccountAlmanacConfig config)
	{
		this.geEventStore = geEventStore;
		this.config = config;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		accountBox.addActionListener(e ->
		{
			if (!populating)
			{
				applyFilters();
			}
		});
		hideCollections.setToolTipText(
			"Leave out the 'collected items' and 'collected coins' rows, so only "
				+ "offers starting, finishing and being cancelled are listed");
		hideCollections.addActionListener(e -> applyFilters());

		summaryLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		controls.add(new JLabel("Account:"));
		controls.add(accountBox);
		controls.add(hideCollections);
		controls.add(summaryLabel);

		JTabbedPane views = new JTabbedPane();
		// Column 0 is the timestamp on the three log views, so newest first;
		// on the rollup it is the item name, where the useful default is
		// instead the largest total spend (column 3).
		views.addTab("All events", new JScrollPane(buildTable(allModel, 0)));
		views.addTab("Purchases", new JScrollPane(buildTable(purchaseModel, 0)));
		views.addTab("Sales", new JScrollPane(buildTable(saleModel, 0)));
		views.addTab("By item", new JScrollPane(buildTable(costBasisModel, 3)));

		add(controls, BorderLayout.NORTH);
		add(views, BorderLayout.CENTER);
	}

	private JTable buildTable(AbstractTableModel model, int defaultSortColumn)
	{
		JTable table = new JTable(model);
		table.setAutoCreateRowSorter(true);
		table.setRowHeight(22);
		table.setFillsViewportHeight(true);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		// Descending on the column that matters for this view: newest first for
		// the logs, biggest spend first for the rollup. Sorted explicitly rather
		// than relying on insertion order, so it survives the user sorting on
		// another column and clicking back.
		table.getRowSorter().toggleSortOrder(defaultSortColumn);
		table.getRowSorter().toggleSortOrder(defaultSortColumn);

		for (int column = 0; column < model.getColumnCount(); column++)
		{
			Class<?> type = model.getColumnClass(column);
			if (type == Long.class)
			{
				table.getColumnModel().getColumn(column).setCellRenderer(gpRenderer());
			}
			else if (type == Double.class)
			{
				table.getColumnModel().getColumn(column).setCellRenderer(percentRenderer());
			}
		}
		table.getColumnModel().getColumn(0).setPreferredWidth(130);
		return table;
	}

	/** Supplies the sprite cache used by the item column. */
	void setItemIcons(ItemIconCache icons)
	{
		this.itemIcons = icons;
	}

	/** Supplies current market prices, for the gain-potential column. */
	void setMarketPrices(Map<Integer, Integer> prices)
	{
		this.marketPrices = prices == null ? java.util.Collections.emptyMap() : prices;
	}

	/** Points the panel at a name resolver, for the privacy setting. */
	void setLabelResolver(java.util.function.Function<GeEvent, String> resolver)
	{
		this.labelResolver = resolver == null ? (e -> e.accountLabel) : resolver;
	}

	private String labelFor(GeEvent event)
	{
		String resolved = labelResolver.apply(event);
		return resolved == null || resolved.isEmpty() ? "-" : resolved;
	}

	/** Reads the log and repopulates every view. */
	void reload()
	{
		events = geEventStore.getEventsNewestFirst();
		refreshAccountOptions();
		applyFilters();
	}

	private void refreshAccountOptions()
	{
		Set<String> labels = new LinkedHashSet<>();
		labels.add(ALL_ACCOUNTS);
		for (GeEvent event : events)
		{
			String label = labelFor(event);
			if (!"-".equals(label))
			{
				labels.add(label);
			}
		}

		String selected = (String) accountBox.getSelectedItem();
		populating = true;
		try
		{
			accountBox.setModel(new DefaultComboBoxModel<>(labels.toArray(new String[0])));
			accountBox.setSelectedItem(labels.contains(selected) ? selected : ALL_ACCOUNTS);
		}
		finally
		{
			populating = false;
		}
	}

	private void applyFilters()
	{
		String account = (String) accountBox.getSelectedItem();
		boolean allAccounts = account == null || ALL_ACCOUNTS.equals(account);

		List<GeEvent> all = new ArrayList<>();
		List<GeEvent> purchases = new ArrayList<>();
		List<GeEvent> sales = new ArrayList<>();

		for (GeEvent event : events)
		{
			if (!allAccounts && !account.equals(labelFor(event)))
			{
				continue;
			}

			GeEventType type = event.typeOrNull();
			if (type == null)
			{
				// An event type written by a newer version. Keep it in the raw
				// log rather than dropping it, but it cannot be classified.
				all.add(event);
				continue;
			}

			boolean isCollection = type == GeEventType.COLLECTED_ITEMS
				|| type == GeEventType.COLLECTED_COINS;
			if (!(hideCollections.isSelected() && isCollection))
			{
				all.add(event);
			}

			if (type.isTradeOutcome() && event.quantity > 0)
			{
				if (type.isSellSide())
				{
					sales.add(event);
				}
				else
				{
					purchases.add(event);
				}
			}
		}

		allModel.setEvents(all, this::labelFor);
		purchaseModel.setEvents(purchases, this::labelFor);
		purchaseModel.setMarketPrices(marketPrices);
		saleModel.setEvents(sales, this::labelFor);
		saleModel.setMarketPrices(marketPrices);
		costBasisModel.setTrades(purchases, sales);

		long spent = 0L;
		for (GeEvent event : purchases)
		{
			spent += event.totalValue;
		}
		long earned = 0L;
		for (GeEvent event : sales)
		{
			earned += event.totalValue;
		}

		summaryLabel.setText(String.format(Locale.ROOT,
			"   %d events  -  %d purchases (%s spent)  -  %d sales (%s received)",
			all.size(), purchases.size(), Format.gp(spent), sales.size(), Format.gp(earned)));
	}

	/**
	 * A signed percentage, or a dash when there is nothing to compare against.
	 *
	 * <p>Green and red here mean "the market moved in your favour" and "against
	 * it", which is why they are not the game's stack colours - this is a
	 * judgement about a trade, not a magnitude.
	 */
	private static DefaultTableCellRenderer percentRenderer()
	{
		return new DefaultTableCellRenderer()
		{
			@Override
			public Component getTableCellRendererComponent(JTable t, Object value,
				boolean selected, boolean focused, int row, int column)
			{
				super.getTableCellRendererComponent(t, value, selected, focused, row, column);
				double pct = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;

				if (Double.isNaN(pct))
				{
					setText("-");
					setToolTipText("No current price known for this item");
				}
				else
				{
					setText(String.format(Locale.ROOT, "%+.0f%%", pct));
					setToolTipText(pct >= 0
						? "Worth more now than it was traded at"
						: "Worth less now than it was traded at");
					if (!selected)
					{
						setForeground(pct >= 0 ? new Color(106, 176, 106) : new Color(198, 91, 91));
					}
				}
				setHorizontalAlignment(SwingConstants.RIGHT);
				return this;
			}
		};
	}

	private static DefaultTableCellRenderer gpRenderer()
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
					setText(Format.gpBare(amount));
					setToolTipText(Format.exact(amount));
				}
				setHorizontalAlignment(SwingConstants.RIGHT);
				return this;
			}
		};
	}

	/**
	 * Per-item cost basis, rolled up from the trade outcomes in the log.
	 *
	 * <p>Answers "what have I actually paid for this item, and what have I sold
	 * it for" - which the chronological log cannot, once an item has been
	 * traded across several offers and several accounts.
	 *
	 * <p>Averages are weighted by quantity rather than taken as a mean of the
	 * per-offer prices. Ten bought at 100 and two thousand bought at 50 average
	 * to 50.2, not to 75.
	 */
	private static class CostBasisTableModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {
			"Item", "Bought", "Avg paid", "Total spent",
			"Sold", "Avg sold", "Total received", "Realised P/L"
		};

		private static class Row
		{
			String name = "";
			long boughtQty;
			long spent;
			long soldQty;
			long received;

			long avgPaid()
			{
				return boughtQty > 0 ? spent / boughtQty : 0L;
			}

			long avgSold()
			{
				return soldQty > 0 ? received / soldQty : 0L;
			}

			/**
			 * Profit on the quantity both bought and sold, at average prices.
			 *
			 * <p>Deliberately limited to the matched quantity. Stock sold that
			 * was acquired before logging began has no recorded cost, and
			 * counting it would report the entire sale price as profit.
			 */
			long realised()
			{
				long matched = Math.min(boughtQty, soldQty);
				return matched > 0 ? matched * (avgSold() - avgPaid()) : 0L;
			}
		}

		private List<Row> rows = new ArrayList<>();

		void setTrades(List<GeEvent> purchases, List<GeEvent> sales)
		{
			Map<Integer, Row> byId = new LinkedHashMap<>();

			for (GeEvent event : purchases)
			{
				Row row = byId.computeIfAbsent(event.itemId, id -> new Row());
				row.name = event.itemName.isEmpty() ? ("Item " + event.itemId) : event.itemName;
				row.boughtQty += event.quantity;
				row.spent += event.totalValue;
			}
			for (GeEvent event : sales)
			{
				Row row = byId.computeIfAbsent(event.itemId, id -> new Row());
				if (row.name.isEmpty())
				{
					row.name = event.itemName.isEmpty() ? ("Item " + event.itemId) : event.itemName;
				}
				row.soldQty += event.quantity;
				row.received += event.totalValue;
			}

			this.rows = new ArrayList<>(byId.values());
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
			return column == 0 ? String.class : Long.class;
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			Row entry = rows.get(row);
			switch (column)
			{
				case 0:
					return entry.name;
				case 1:
					return entry.boughtQty;
				case 2:
					return entry.avgPaid();
				case 3:
					return entry.spent;
				case 4:
					return entry.soldQty;
				case 5:
					return entry.avgSold();
				case 6:
					return entry.received;
				case 7:
					return entry.realised();
				default:
					return "";
			}
		}
	}

	/**
	 * Base model holding the filtered event list. Timestamps are exposed as a
	 * formatted string that sorts correctly as text, because the format is
	 * {@code yyyy-MM-dd HH:mm} - lexical order and chronological order agree.
	 */
	private abstract static class BaseModel extends AbstractTableModel
	{
		List<GeEvent> rows = new ArrayList<>();
		java.util.function.Function<GeEvent, String> labels = e -> e.accountLabel;

		void setEvents(List<GeEvent> events, java.util.function.Function<GeEvent, String> labels)
		{
			this.rows = events;
			this.labels = labels;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}
	}

	/** The raw log: one row per event, whatever kind it is. */
	private static class EventTableModel extends BaseModel
	{
		private static final String[] COLUMNS = {
			"When", "Account", "Event", "Item", "Quantity", "Unit price", "Value", "Slot"
		};

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
				case 4:
					return Integer.class;
				case 5:
				case 6:
					return Long.class;
				case 7:
					return Integer.class;
				default:
					return String.class;
			}
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			GeEvent event = rows.get(row);
			switch (column)
			{
				case 0:
					// An approximate timestamp is marked, because it is when the
					// change was noticed rather than when it happened.
					return LoginAge.exact(event.at) + (event.approximate ? " ~" : "");
				case 1:
					return labels.apply(event);
				case 2:
					return event.typeLabel();
				case 3:
					return event.itemName.isEmpty() ? "-" : event.itemName;
				case 4:
					return event.quantity;
				case 5:
					return (long) event.pricePerItem;
				case 6:
					return event.totalValue;
				case 7:
					return event.slot < 0 ? 0 : event.slot + 1;
				default:
					return "";
			}
		}
	}

	/**
	 * Purchase or sale history: what was traded, how much of it, at what price
	 * and when.
	 *
	 * <p>Both the listed price and the price actually transacted are shown.
	 * They differ often and meaningfully - a buy offer fills at or below the
	 * price you set, so the listed price is what you were willing to pay while
	 * the actual is what you paid.
	 */
	private static class TradeTableModel extends BaseModel
	{
		private final String[] columns;

		private Map<Integer, Integer> marketPrices = java.util.Collections.emptyMap();

		TradeTableModel(boolean sellSide)
		{
			this.columns = new String[]{
				"When", "Account", "Item", "Quantity",
				"Listed price", sellSide ? "Received per item" : "Paid per item",
				sellSide ? "Total received" : "Total paid",
				"Market now", "vs market", "Outcome"
			};
		}

		void setMarketPrices(Map<Integer, Integer> prices)
		{
			this.marketPrices = prices == null ? java.util.Collections.emptyMap() : prices;
			fireTableDataChanged();
		}

		@Override
		public int getColumnCount()
		{
			return columns.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return columns[column];
		}

		@Override
		public Class<?> getColumnClass(int column)
		{
			switch (column)
			{
				case 3:
					return Integer.class;
				case 4:
				case 5:
				case 6:
				case 7:
					return Long.class;
				case 8:
					return Double.class;
				default:
					return String.class;
			}
		}

		/**
		 * How far the price transacted sits from what the item is worth now.
		 *
		 * <p>On a purchase this is the gain still on the table: buying at 1 gp
		 * something now worth 9,000 is not visible from the paid price alone.
		 * On a sale it reads the other way - positive means the market has
		 * risen since, so it went too cheap.
		 */
		private double versusMarket(GeEvent event)
		{
			Integer market = marketPrices.get(event.itemId);
			long paid = event.actualUnitPrice();
			if (market == null || market <= 0 || paid <= 0)
			{
				return Double.NaN;
			}
			return 100.0 * (market - paid) / (double) paid;
		}

		@Override
		public Object getValueAt(int row, int column)
		{
			GeEvent event = rows.get(row);
			switch (column)
			{
				case 0:
					return LoginAge.exact(event.at) + (event.approximate ? " ~" : "");
				case 1:
					return labels.apply(event);
				case 2:
					return event.itemName.isEmpty() ? "-" : event.itemName;
				case 3:
					return event.quantity;
				case 4:
					return (long) event.pricePerItem;
				case 5:
					return event.actualUnitPrice();
				case 6:
					return event.totalValue;
				case 7:
				{
					Integer market = marketPrices.get(event.itemId);
					return market == null ? 0L : (long) market;
				}
				case 8:
					return versusMarket(event);
				case 9:
				{
					GeEventType type = event.typeOrNull();
					if (type == GeEventType.BUY_CANCELLED || type == GeEventType.SELL_CANCELLED)
					{
						return "Cancelled part-filled";
					}
					return "Completed";
				}
				default:
					return "";
			}
		}
	}
}
