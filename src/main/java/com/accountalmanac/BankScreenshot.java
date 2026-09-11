package com.accountalmanac;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import net.runelite.client.ui.FontManager;

/**
 * Renders a stored bank snapshot as an image styled after the in-game bank.
 *
 * <p>Drawn from the stored snapshot rather than captured from the live bank
 * widget. The plugin exists for the accounts you are not logged into, and a
 * widget capture can only ever photograph the one in front of you. Working
 * from stored data also lets the grid be ordered and filtered however the user
 * asks, which the game's own bank cannot do.
 *
 * <p>The frame is the game's own: the steel border corners and edges and the
 * bank tab sprites are fetched from the client's sprite cache and tiled here,
 * so the window is the real one rather than an impression of it. When a sprite
 * has not loaded - or there is no client at all - each piece falls back to a
 * drawn bevel, so the export still works and simply looks plainer.
 *
 * <p>Everything else is plain drawing with no client access and no IO, so it
 * is safe on the Swing thread.
 */
final class BankScreenshot
{
	/** Sprite ids for the pieces of the game's own window frame. */
	static final int SPRITE_CORNER_TL = 310;
	static final int SPRITE_CORNER_TR = 311;
	static final int SPRITE_CORNER_BL = 312;
	static final int SPRITE_CORNER_BR = 313;
	static final int SPRITE_EDGE_TOP = 314;
	static final int SPRITE_EDGE_SIDE = 315;
	static final int SPRITE_TAB = 1077;
	static final int SPRITE_TAB_SELECTED = 1079;
	static final int SPRITE_TAB_ICON_ALL = 1081;

	/** Every sprite this renderer asks for, so callers can prefetch them. */
	static int[] framePieces()
	{
		return new int[]{
			SPRITE_CORNER_TL, SPRITE_CORNER_TR, SPRITE_CORNER_BL, SPRITE_CORNER_BR,
			SPRITE_EDGE_TOP, SPRITE_EDGE_SIDE,
			SPRITE_TAB, SPRITE_TAB_SELECTED, SPRITE_TAB_ICON_ALL,
		};
	}

	/** One drawn slot and the item that landed in it. */
	static final class Slot
	{
		final Rectangle bounds;
		final BankItem item;

		Slot(Rectangle bounds, BankItem item)
		{
			this.bounds = bounds;
			this.item = item;
		}
	}

	/**
	 * A finished image together with where each item was drawn.
	 *
	 * <p>The slot map is returned rather than recomputed by the caller because
	 * only the renderer knows the column count it settled on - recalculating it
	 * outside would be a second implementation of the same layout, free to
	 * drift out of step with this one.
	 */
	static final class Rendered
	{
		final BufferedImage image;
		final List<Slot> slots;

		Rendered(BufferedImage image, List<Slot> slots)
		{
			this.image = image;
			this.slots = slots;
		}

		/** The item drawn at a point, or null if the point is not on one. */
		BankItem itemAt(int x, int y)
		{
			for (Slot slot : slots)
			{
				if (slot.bounds.contains(x, y))
				{
					return slot.item;
				}
			}
			return null;
		}
	}

	/** How the grid is ordered. */
	enum Sort
	{
		VALUE("Stack value, high to low"),
		QUANTITY("Stack size, high to low"),
		ALCH("Alch value, high to low"),
		NAME("Name, A to Z");

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

		Comparator<BankItem> comparator()
		{
			switch (this)
			{
				case QUANTITY:
					return Comparator.comparingLong((BankItem i) -> i.quantity).reversed();
				case ALCH:
					return Comparator.comparingLong(BankItem::totalHaValue).reversed();
				case NAME:
					return Comparator.comparing(i -> i.name == null ? "" : i.name,
						String.CASE_INSENSITIVE_ORDER);
				case VALUE:
				default:
					return Comparator.comparingLong(BankItem::totalValue).reversed();
			}
		}
	}

	/** Smallest stack size worth drawing. */
	enum MinQuantity
	{
		ANY("Any quantity", 0),
		TEN("10 or more", 10),
		FIFTY("50 or more", 50),
		HUNDRED("100 or more", 100),
		THOUSAND("1,000 or more", 1_000),
		K5("5,000 or more", 5_000),
		K10("10,000 or more", 10_000),
		K50("50,000 or more", 50_000);

		final String label;
		final int floor;

		MinQuantity(String label, int floor)
		{
			this.label = label;
			this.floor = floor;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Smallest stack value worth drawing. */
	enum MinValue
	{
		ANY("Any value", 0L),
		K10("10K or more", 10_000L),
		K50("50K or more", 50_000L),
		K100("100K or more", 100_000L),
		M1("1M or more", 1_000_000L),
		M10("10M or more", 10_000_000L);

		final String label;
		final long floor;

		MinValue(String label, long floor)
		{
			this.label = label;
			this.floor = floor;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Members-only, free-to-play, or both. */
	enum Membership
	{
		ANY("All items"),
		FREE("Free-to-play"),
		MEMBERS("Members only");

		final String label;

		Membership(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}

		/**
		 * An item with no captured flag passes either way.
		 *
		 * <p>Unknown is not evidence of either answer, and hiding those items
		 * would understate both sides. A price refresh fills the flag in.
		 */
		boolean accepts(Boolean members)
		{
			if (this == ANY || members == null)
			{
				return true;
			}
			return this == MEMBERS ? members : !members;
		}
	}

	// The game's slots are 36 x 32. The column count is not fixed at the
	// game's eight, because a roster-wide bank at eight across is a tall
	// ribbon that no viewer can zoom usefully.
	private static final int SLOT_W = 36;
	private static final int SLOT_H = 32;
	private static final int MIN_COLS = 8;
	private static final int MAX_COLS = 30;

	// The steel frame's own thickness, and the chrome inside it.
	private static final int EDGE = 8;
	private static final int PAD = 6;
	private static final int TITLE_H = 22;
	private static final int INFO_H = 16;
	private static final int TAB_H = 36;
	private static final int FOOT_H = 20;

	private static final Color STONE = new Color(0x3E, 0x35, 0x29);
	private static final Color WELL = new Color(0x33, 0x2C, 0x22);
	private static final Color BEVEL_HI = new Color(0x6B, 0x60, 0x4E);
	private static final Color BEVEL_LO = new Color(0x1E, 0x1A, 0x13);
	private static final Color ORANGE = new Color(0xFF, 0x98, 0x1F);
	private static final Color PARCHMENT = new Color(0xDC, 0xCF, 0xA8);

	private static final String DOT = "  -  ";

	private BankScreenshot()
	{
	}

	/**
	 * Keeps an item when it clears <em>either</em> threshold.
	 *
	 * <p>Either, not both, on purpose. A quantity floor is there to sweep out
	 * the single leftovers, but applied alone it would also throw away the one
	 * item that matters most - a party hat is a stack of one. Letting a high
	 * value rescue a small stack is what makes the quantity filter safe to use.
	 */
	static boolean keeps(BankItem item, MinQuantity minQuantity, MinValue minValue,
		Membership membership)
	{
		if (item == null || item.quantity <= 0)
		{
			return false;
		}
		if (!membership.accepts(item.members))
		{
			return false;
		}
		if (minQuantity.floor == 0 && minValue.floor == 0L)
		{
			return true;
		}
		boolean byQuantity = minQuantity.floor > 0 && item.quantity >= minQuantity.floor;
		boolean byValue = minValue.floor > 0L && item.totalValue() >= minValue.floor;
		return byQuantity || byValue;
	}

	/**
	 * Columns that keep the picture roughly landscape.
	 *
	 * <p>Eight across is the game's width and fine for one bank, but a
	 * roster-wide export at eight is a column of items thousands of pixels
	 * tall: it cannot be zoomed to read, and most of the screen is wasted.
	 */
	static int autoColumns(int itemCount)
	{
		if (itemCount <= MIN_COLS)
		{
			return MIN_COLS;
		}
		double target = Math.sqrt(itemCount * 1.5d * SLOT_H / SLOT_W);
		int cols = (int) Math.ceil(target);
		return Math.max(MIN_COLS, Math.min(MAX_COLS, cols));
	}

	/**
	 * @param title       heading, normally the account's display name
	 * @param items       the snapshot to draw; not modified
	 * @param sort        ordering applied before the grid is laid out
	 * @param icons       item id to sprite, may return null
	 * @param subtitle    footer note, e.g. when the bank was last seen
	 * @param minQuantity smallest stack size drawn
	 * @param minValue    smallest stack value drawn
	 * @param membership  members-only, free-to-play, or both
	 * @param columns     grid width, or 0 to size it automatically
	 * @param frame       sprite id to the game's frame piece, may return null
	 */
	static Rendered render(String title, List<BankItem> items, Sort sort,
		IntFunction<Image> icons, String subtitle,
		MinQuantity minQuantity, MinValue minValue, Membership membership, int columns,
		IntFunction<BufferedImage> frame)
	{
		List<BankItem> ordered = new ArrayList<>();
		long hiddenValue = 0L;
		int hidden = 0;
		for (BankItem item : items)
		{
			if (item == null || item.quantity <= 0)
			{
				continue;
			}
			if (keeps(item, minQuantity, minValue, membership))
			{
				ordered.add(item);
			}
			else
			{
				hidden++;
				hiddenValue += item.totalValue();
			}
		}
		ordered.sort(sort.comparator());

		int cols = columns > 0 ? columns : autoColumns(ordered.size());
		int rows = Math.max(1, (ordered.size() + cols - 1) / cols);
		int gridW = cols * SLOT_W;
		int gridH = rows * SLOT_H;

		int width = EDGE * 2 + PAD * 2 + gridW;
		int chromeTop = TITLE_H + INFO_H + TAB_H;
		int height = EDGE * 2 + chromeTop + gridH + FOOT_H + PAD;

		long totalValue = 0L;
		for (BankItem item : ordered)
		{
			totalValue += item.totalValue();
		}

		List<Slot> slots = new ArrayList<>(ordered.size());
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try
		{
			// The game does not antialias its text, and switching it on makes
			// the RuneScape faces look smeared rather than smooth.
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

			stone(g, width, height);
			steelFrame(g, width, height, frame);

			Font rsBold = FontManager.getRunescapeBoldFont();
			Font rsSmall = FontManager.getRunescapeSmallFont();

			String heading = title == null || title.isEmpty() ? "Bank" : title;
			g.setFont(rsBold);
			int tw = g.getFontMetrics().stringWidth(heading);
			shadowed(g, heading, (width - tw) / 2, EDGE + 15, ORANGE);

			g.setFont(rsSmall);
			String info = Format.exact(ordered.size()) + " items" + DOT
				+ Format.exact(totalValue) + " gp";
			int iw = g.getFontMetrics().stringWidth(info);
			shadowed(g, info, (width - iw) / 2, EDGE + TITLE_H + 11, PARCHMENT);

			int gx = EDGE + PAD;
			int tabsY = EDGE + TITLE_H + INFO_H;
			tabStrip(g, gx, tabsY, gridW, frame);

			int gy = tabsY + TAB_H;
			g.setColor(WELL);
			g.fillRect(gx, gy, gridW, gridH);

			for (int i = 0; i < ordered.size(); i++)
			{
				BankItem item = ordered.get(i);
				int sx = gx + (i % cols) * SLOT_W;
				int sy = gy + (i / cols) * SLOT_H;
				slots.add(new Slot(new Rectangle(sx, sy, SLOT_W, SLOT_H), item));

				Image sprite = icons == null ? null : icons.apply(item.id);
				if (sprite != null)
				{
					int w = sprite.getWidth(null);
					int h = sprite.getHeight(null);
					// An unloaded sprite reports -1; centring on the nominal
					// size is harmless and it fills in on the next render.
					int dx = sx + (SLOT_W - (w > 0 ? w : SLOT_W)) / 2;
					int dy = sy + (SLOT_H - (h > 0 ? h : SLOT_H)) / 2;
					g.drawImage(sprite, dx, dy, null);
				}

				g.setFont(rsSmall);
				shadowed(g, StackFormat.text(item.quantity), sx + 1, sy + 10,
					StackFormat.colour(item.quantity));
			}

			g.setFont(rsSmall);
			StringBuilder foot = new StringBuilder("Sorted by ").append(sort);
			if (hidden > 0)
			{
				// What was left out is stated rather than silently dropped - an
				// export that quietly omits things is worse than a longer one.
				foot.append(DOT).append(Format.exact(hidden)).append(" hidden (")
					.append(Format.gp(hiddenValue)).append(')');
			}
			if (subtitle != null && !subtitle.isEmpty())
			{
				foot.append(DOT).append(subtitle);
			}
			shadowed(g, foot.toString(), gx, gy + gridH + 14, PARCHMENT);
		}
		finally
		{
			g.dispose();
		}
		return new Rendered(image, slots);
	}

	/**
	 * The game's steel window frame: four corner sprites with the edge sprites
	 * tiled between them.
	 *
	 * <p>The side edge sprite is drawn rotated for the left-hand run, which is
	 * what the interface itself does - the cache carries one edge per axis,
	 * not one per side.
	 */
	private static void steelFrame(Graphics2D g, int width, int height,
		IntFunction<BufferedImage> frame)
	{
		BufferedImage tl = sprite(frame, SPRITE_CORNER_TL);
		BufferedImage tr = sprite(frame, SPRITE_CORNER_TR);
		BufferedImage bl = sprite(frame, SPRITE_CORNER_BL);
		BufferedImage br = sprite(frame, SPRITE_CORNER_BR);
		BufferedImage top = sprite(frame, SPRITE_EDGE_TOP);
		BufferedImage side = sprite(frame, SPRITE_EDGE_SIDE);

		if (tl == null || top == null || side == null)
		{
			// No sprite cache available - fall back to a drawn bevel so the
			// export still frames itself.
			bevel(g, 0, 0, width, height, true);
			bevel(g, 1, 1, width - 2, height - 2, true);
			return;
		}

		int cw = tl.getWidth();
		int ch = tl.getHeight();

		Graphics2D h = (Graphics2D) g.create();
		try
		{
			h.setPaint(new TexturePaint(top, new Rectangle(0, 0, top.getWidth(), top.getHeight())));
			h.fillRect(cw, 0, Math.max(0, width - cw * 2), top.getHeight());
			h.fillRect(cw, height - top.getHeight(), Math.max(0, width - cw * 2), top.getHeight());
		}
		finally
		{
			h.dispose();
		}

		Graphics2D v = (Graphics2D) g.create();
		try
		{
			v.setPaint(new TexturePaint(side, new Rectangle(0, 0, side.getWidth(), side.getHeight())));
			v.fillRect(0, ch, side.getWidth(), Math.max(0, height - ch * 2));
			v.fillRect(width - side.getWidth(), ch, side.getWidth(), Math.max(0, height - ch * 2));
		}
		finally
		{
			v.dispose();
		}

		g.drawImage(tl, 0, 0, null);
		if (tr != null)
		{
			g.drawImage(tr, width - tr.getWidth(), 0, null);
		}
		if (bl != null)
		{
			g.drawImage(bl, 0, height - bl.getHeight(), null);
		}
		if (br != null)
		{
			g.drawImage(br, width - br.getWidth(), height - br.getHeight(), null);
		}
	}

	/** The row of tab buttons above the bank's item area. */
	private static void tabStrip(Graphics2D g, int x, int y, int w,
		IntFunction<BufferedImage> frame)
	{
		BufferedImage tab = sprite(frame, SPRITE_TAB);
		BufferedImage selected = sprite(frame, SPRITE_TAB_SELECTED);
		BufferedImage icon = sprite(frame, SPRITE_TAB_ICON_ALL);

		if (tab == null)
		{
			g.setColor(BEVEL_LO);
			g.drawLine(x, y + TAB_H - 2, x + w - 1, y + TAB_H - 2);
			return;
		}

		int tabW = tab.getWidth();
		int count = Math.max(1, Math.min(10, w / tabW));
		for (int i = 0; i < count; i++)
		{
			BufferedImage face = i == 0 && selected != null ? selected : tab;
			int tx = x + i * tabW;
			g.drawImage(face, tx, y + (TAB_H - face.getHeight()), null);
			if (i == 0 && icon != null)
			{
				g.drawImage(icon,
					tx + (tabW - icon.getWidth()) / 2,
					y + TAB_H - face.getHeight() + (face.getHeight() - icon.getHeight()) / 2,
					null);
			}
		}
	}

	private static BufferedImage sprite(IntFunction<BufferedImage> frame, int id)
	{
		return frame == null ? null : frame.apply(id);
	}

	/**
	 * The interface's stone ground, behind the frame.
	 *
	 * <p>Generated from the pixel coordinates rather than a random source, so
	 * two renders of the same bank produce byte-identical images - which
	 * matters if anyone diffs or dedupes them. Written into a raster in one
	 * pass rather than as a million one-pixel fills, which on a large bank is
	 * the difference between instant and visibly slow.
	 */
	private static void stone(Graphics2D g, int width, int height)
	{
		BufferedImage tile = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				int n = ((x * 73856093) ^ (y * 19349663)) >>> 8;
				int d = (n % 11) - 5;
				int rgb = (clamp(STONE.getRed() + d) << 16)
					| (clamp(STONE.getGreen() + d) << 8)
					| clamp(STONE.getBlue() + d);
				tile.setRGB(x, y, rgb);
			}
		}
		g.drawImage(tile, 0, 0, null);
	}

	/** A RuneScape interface bevel: lit from the top-left, or sunken. */
	private static void bevel(Graphics2D g, int x, int y, int w, int h, boolean raised)
	{
		g.setColor(raised ? BEVEL_HI : BEVEL_LO);
		g.drawLine(x, y, x + w - 1, y);
		g.drawLine(x, y, x, y + h - 1);
		g.setColor(raised ? BEVEL_LO : BEVEL_HI);
		g.drawLine(x + w - 1, y, x + w - 1, y + h - 1);
		g.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
	}

	/** Game text is drawn with a hard black shadow one pixel down and right. */
	private static void shadowed(Graphics2D g, String text, int x, int y, Color colour)
	{
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(colour);
		g.drawString(text, x, y);
	}

	private static int clamp(int v)
	{
		return v < 0 ? 0 : Math.min(v, 255);
	}
}
