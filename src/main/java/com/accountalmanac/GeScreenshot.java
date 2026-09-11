package com.accountalmanac;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import net.runelite.client.ui.FontManager;

/**
 * Renders stored Grand Exchange offers as an image styled after the in-game
 * Grand Exchange.
 *
 * <p>Drawn from stored offers rather than the live interface, for the same
 * reason the bank export is: the offers worth seeing together belong to
 * accounts that are not logged in, and the game can only show you the eight
 * slots in front of you. This lays every tracked account's slots out at once.
 *
 * <p>The window frame, stone ground and text shadowing are shared with
 * {@link BankScreenshot}, so the two exports are visibly the same interface.
 *
 * <p>Pure drawing - no client access and no IO - so it is safe on the Swing
 * thread. Item sprites arrive through the supplied resolver; a null simply
 * leaves that slot without one.
 */
final class GeScreenshot
{
	/** Which side of the book to draw. */
	enum Side
	{
		ALL("Buys and sells"),
		BUY("Buy offers only"),
		SELL("Sell offers only");

		private final String label;

		Side(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}

		boolean accepts(GrandExchangeRecord offer)
		{
			switch (this)
			{
				case BUY:
					return offer.isBuy();
				case SELL:
					return offer.isSell();
				case ALL:
				default:
					return true;
			}
		}
	}

	/** How the offer boxes are ordered. */
	enum Sort
	{
		PROGRESS_DESC("Closest to done first"),
		PROGRESS_ASC("Furthest from done first"),
		COMMITTED_DESC("Most gp committed first"),
		COMMITTED_ASC("Least gp committed first"),
		UNIT_PRICE_DESC("Highest unit price first"),
		ACCOUNT("Account, A to Z"),
		ITEM("Item name, A to Z");

		private final String label;

		Sort(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}

		Comparator<Entry> comparator()
		{
			Comparator<Entry> byAccount =
				Comparator.comparing(e -> e.account == null ? "" : e.account,
					String.CASE_INSENSITIVE_ORDER);
			switch (this)
			{
				case PROGRESS_ASC:
					return Comparator.comparingDouble((Entry e) -> e.offer.progress())
						.thenComparing(byAccount);
				case COMMITTED_DESC:
					return Comparator.comparingLong((Entry e) -> e.offer.committedValue())
						.reversed().thenComparing(byAccount);
				case COMMITTED_ASC:
					return Comparator.comparingLong((Entry e) -> e.offer.committedValue())
						.thenComparing(byAccount);
				case UNIT_PRICE_DESC:
					return Comparator.comparingLong((Entry e) -> e.offer.pricePerItem)
						.reversed().thenComparing(byAccount);
				case ACCOUNT:
					return byAccount.thenComparing(e -> e.offer.itemName == null ? "" : e.offer.itemName,
						String.CASE_INSENSITIVE_ORDER);
				case ITEM:
					return Comparator.comparing((Entry e) -> e.offer.itemName == null ? "" : e.offer.itemName,
						String.CASE_INSENSITIVE_ORDER).thenComparing(byAccount);
				case PROGRESS_DESC:
				default:
					return Comparator.comparingDouble((Entry e) -> e.offer.progress())
						.reversed().thenComparing(byAccount);
			}
		}
	}

	/** One offer together with the account holding it. */
	static final class Entry
	{
		final String account;
		final GrandExchangeRecord offer;

		Entry(String account, GrandExchangeRecord offer)
		{
			this.account = account;
			this.offer = offer;
		}
	}

	// A slot box, sized so the item, its name and a progress bar fit without
	// the name having to be truncated for anything but the longest items.
	private static final int BOX_W = 172;
	private static final int BOX_H = 92;
	private static final int GAP = 6;
	private static final int COLS = 4;

	private static final int EDGE = 8;
	private static final int PAD = 8;
	private static final int TITLE_H = 22;
	private static final int INFO_H = 16;
	private static final int FOOT_H = 20;

	// The game colours the two sides of the book differently, and the progress
	// bar goes green only once an offer has actually completed.
	private static final Color BUY = new Color(0x6A, 0xA8, 0x4F);
	private static final Color SELL = new Color(0xC0, 0x6C, 0x3E);
	private static final Color BAR_TRACK = new Color(0x22, 0x1D, 0x16);
	private static final Color BAR_DONE = new Color(0x3C, 0xA0, 0x3C);
	private static final Color WHITE = new Color(0xE6, 0xE6, 0xE6);
	// A gap that favours the holder is green; against them, red. Which way
	// round that is depends on the side of the book - see priceGapFavourable.
	private static final Color GOOD = new Color(0x5A, 0xC8, 0x5A);
	private static final Color BAD = new Color(0xD0, 0x50, 0x40);

	private GeScreenshot()
	{
	}

	/**
	 * @param title  heading for the export
	 * @param offers every tracked slot, with the account that holds it
	 * @param side   buys, sells, or both
	 * @param sort   how the boxes are ordered
	 * @param showMarketGap draw how far each listing sits from market price
	 * @param icons  item id to sprite, may return null
	 * @param frame  sprite id to the game's frame piece, may return null
	 */
	static BankScreenshot.Rendered render(String title, List<Entry> offers, Side side,
		Sort sort, boolean showMarketGap,
		IntFunction<Image> icons, IntFunction<BufferedImage> frame)
	{
		List<Entry> shown = new ArrayList<>();
		for (Entry entry : offers)
		{
			// Empty slots are not offers. Including them would pad the picture
			// with blank boxes that say nothing about what is on the market.
			if (entry != null && entry.offer != null && entry.offer.isActive()
				&& side.accepts(entry.offer))
			{
				shown.add(entry);
			}
		}
		shown.sort((sort == null ? Sort.PROGRESS_DESC : sort).comparator());

		int cols = Math.min(COLS, Math.max(1, shown.size()));
		int rows = Math.max(1, (shown.size() + cols - 1) / cols);
		int gridW = cols * BOX_W + (cols - 1) * GAP;
		int gridH = rows * BOX_H + (rows - 1) * GAP;

		int width = EDGE * 2 + PAD * 2 + Math.max(gridW, 320);
		int height = EDGE * 2 + TITLE_H + INFO_H + gridH + FOOT_H + PAD;

		long committed = 0L;
		int buys = 0;
		int sells = 0;
		for (Entry entry : shown)
		{
			committed += entry.offer.committedValue();
			if (entry.offer.isBuy())
			{
				buys++;
			}
			else if (entry.offer.isSell())
			{
				sells++;
			}
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		List<BankScreenshot.Slot> slots = new ArrayList<>(shown.size());
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

			BankScreenshot.stone(g, width, height);
			BankScreenshot.steelFrame(g, width, height, frame);

			Font rsBold = FontManager.getRunescapeBoldFont();
			Font rsSmall = FontManager.getRunescapeSmallFont();

			String heading = title == null || title.isEmpty() ? "Grand Exchange" : title;
			g.setFont(rsBold);
			int tw = g.getFontMetrics().stringWidth(heading);
			BankScreenshot.shadowed(g, heading, (width - tw) / 2, EDGE + 15,
				BankScreenshot.ORANGE);

			g.setFont(rsSmall);
			String info = shown.size() + (shown.size() == 1 ? " offer" : " offers")
				+ "  -  " + buys + " buying, " + sells + " selling"
				+ "  -  " + Format.exact(committed) + " gp committed";
			int iw = g.getFontMetrics().stringWidth(info);
			BankScreenshot.shadowed(g, info, (width - iw) / 2, EDGE + TITLE_H + 11,
				BankScreenshot.PARCHMENT);

			int gx = EDGE + PAD;
			int gy = EDGE + TITLE_H + INFO_H;

			for (int i = 0; i < shown.size(); i++)
			{
				Entry entry = shown.get(i);
				int bx = gx + (i % cols) * (BOX_W + GAP);
				int by = gy + (i / cols) * (BOX_H + GAP);
				drawSlot(g, bx, by, entry, icons, rsSmall, showMarketGap);
				slots.add(new BankScreenshot.Slot(
					new Rectangle(bx, by, BOX_W, BOX_H), asItem(entry.offer),
					tooltipFor(entry)));
			}

			if (shown.isEmpty())
			{
				g.setFont(rsSmall);
				String none = "No " + (side == Side.ALL ? "" : side.toString().toLowerCase())
					+ " offers are open";
				BankScreenshot.shadowed(g, none, gx, gy + 20, BankScreenshot.PARCHMENT);
			}

			g.setFont(rsSmall);
			BankScreenshot.shadowed(g, side + "  -  "
					+ (sort == null ? Sort.PROGRESS_DESC : sort), gx, gy + gridH + 14,
				BankScreenshot.PARCHMENT);
		}
		finally
		{
			g.dispose();
		}
		return new BankScreenshot.Rendered(image, slots);
	}

	/**
	 * How far a listing sits from the market price, as a percentage.
	 *
	 * <p>Always a percentage, however extreme. A hail-mary listing at 253
	 * times the market reads as +25,171%, and that is the point - the size of
	 * the number is the signal. Grouped so the long ones stay readable.
	 *
	 * <p>Rounded to whole percent: anything finer is noise against a market
	 * price that moves while the offer sits there.
	 */
	static String marketGapLabel(GrandExchangeRecord offer)
	{
		Double gap = offer.priceVsMarket();
		if (gap == null)
		{
			return "";
		}
		long pct = Math.round(gap * 100);
		return (pct > 0 ? "+" : "") + Format.exact(pct) + "%";
	}

	/**
	 * Hover text for an offer box.
	 *
	 * <p>Leads with the account, because that is the thing the picture cannot
	 * show at a glance once a dozen accounts are laid out together.
	 */
	private static String tooltipFor(Entry entry)
	{
		GrandExchangeRecord offer = entry.offer;
		return (entry.account == null || entry.account.isEmpty() ? "Unknown account" : entry.account)
			+ "  -  " + (offer.isBuy() ? "buying " : "selling ") + offer.itemName
			+ "  -  " + Format.exact(offer.quantitySold) + " / " + Format.exact(offer.totalQuantity)
			+ " (" + Math.round(offer.progress() * 100) + "%)"
			+ "  -  " + Format.exact(offer.pricePerItem) + " gp each"
			+ (offer.priceVsMarket() == null ? "  (market price unknown)"
				: "  (" + marketGapLabel(offer) + " vs market "
					+ Format.exact(offer.marketPrice) + " gp)")
			+ "  -  " + Format.gp(offer.committedValue()) + " committed";
	}

	/** One offer box: account, item, progress bar and the price line. */
	private static void drawSlot(Graphics2D g, int x, int y, Entry entry,
		IntFunction<Image> icons, Font rsSmall, boolean showMarketGap)
	{
		GrandExchangeRecord offer = entry.offer;
		Color accent = offer.isBuy() ? BUY : SELL;

		g.setColor(BankScreenshot.WELL);
		g.fillRect(x, y, BOX_W, BOX_H);
		BankScreenshot.bevel(g, x, y, BOX_W, BOX_H, false);

		// A coloured strip along the top is how the side reads at a glance.
		g.setColor(accent);
		g.fillRect(x + 1, y + 1, BOX_W - 2, 3);

		g.setFont(rsSmall);
		BankScreenshot.shadowed(g, offer.isBuy() ? "Buy" : "Sell", x + 5, y + 16, accent);

		String account = entry.account == null ? "" : entry.account;
		int aw = g.getFontMetrics().stringWidth(account);
		BankScreenshot.shadowed(g, account, x + BOX_W - 5 - aw, y + 16,
			BankScreenshot.PARCHMENT);

		Image sprite = icons == null ? null : icons.apply(offer.itemId);
		if (sprite != null)
		{
			g.drawImage(sprite, x + 5, y + 22, null);
		}

		BankScreenshot.shadowed(g, trim(g, offer.itemName, BOX_W - 52),
			x + 44, y + 34, WHITE);

		// Progress across the bottom, filled to what has actually traded.
		int barX = x + 44;
		int barY = y + 42;
		int barW = BOX_W - 49;
		g.setColor(BAR_TRACK);
		g.fillRect(barX, barY, barW, 7);
		double p = offer.progress();
		int fill = (int) Math.round(barW * p);
		if (fill > 0)
		{
			g.setColor(ProgressColours.forFraction(p));
			g.fillRect(barX, barY, fill, 7);
		}
		BankScreenshot.bevel(g, barX, barY, barW, 7, false);

		// Three figures on one line was the bug: unit price, the market badge
		// and the committed value all drew at y+80 and ran into each other on
		// anything wide. Committed moves up to share the quantity line, and
		// each pair is laid out left-and-right with an overlap guard.
		String qty = Format.exact(offer.quantitySold) + " / " + Format.exact(offer.totalQuantity);
		String committed = Format.gp(offer.committedValue());
		pair(g, x, y + 66, qty, BankScreenshot.PARCHMENT, committed, accent);

		String unit = Format.exact(offer.pricePerItem) + " gp ea";
		Double gap = offer.priceVsMarket();
		String badge = showMarketGap && gap != null ? marketGapLabel(offer) : "";
		pair(g, x, y + 80, unit, WHITE, badge,
			offer.priceGapFavourable() ? GOOD : BAD);
	}

	/**
	 * Draws one line with text at each end.
	 *
	 * <p>When the two would collide the right-hand figure is dropped rather
	 * than overlapped: a number sitting on top of another number is worse than
	 * a number that is not there, and the hover text still carries it.
	 */
	private static void pair(Graphics2D g, int x, int y,
		String left, Color leftColour, String right, Color rightColour)
	{
		BankScreenshot.shadowed(g, left, x + 5, y, leftColour);
		if (right == null || right.isEmpty())
		{
			return;
		}
		int lw = g.getFontMetrics().stringWidth(left);
		int rw = g.getFontMetrics().stringWidth(right);
		int rx = x + BOX_W - 5 - rw;
		if (rx < x + 5 + lw + 6)
		{
			return;
		}
		BankScreenshot.shadowed(g, right, rx, y, rightColour);
	}

	/**
	 * The offer as a bank item, so the hover map can reuse the bank export's
	 * slot type rather than introducing a parallel one.
	 */
	private static BankItem asItem(GrandExchangeRecord offer)
	{
		return new BankItem(offer.itemId, Math.max(1, offer.totalQuantity),
			offer.itemName, offer.pricePerItem, 0);
	}

	/** Shortens a name to fit, with an ellipsis when it has to cut. */
	private static String trim(Graphics2D g, String text, int maxWidth)
	{
		if (text == null)
		{
			return "";
		}
		if (g.getFontMetrics().stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && g.getFontMetrics().stringWidth(cut + "..") > maxWidth)
		{
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "..";
	}
}
