package com.accountalmanac;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;

/**
 * A replica of the in-game stats interface for one account: three columns
 * by eight rows of skill cells, each with the skill's icon and its level,
 * then a total-level bar underneath.
 *
 * <p>The game shows a boosted level and a real level split diagonally in
 * each cell. Only the real level is ever recorded here - boosts are
 * transient and meaningless once an account is logged out - so this draws
 * one number per cell rather than the same value twice split by a divider,
 * which is what the game itself shows on an unboosted account anyway.
 *
 * <p>Hovering a cell gives the skill name and its exact experience.
 */
class StatsGridPanel extends JPanel
{
	private static final int CELL_WIDTH = 80;
	private static final int CELL_HEIGHT = 32;
	private static final int TOTAL_BAR_HEIGHT = 26;
	private static final int GAP = 2;
	private static final int TEXT_PADDING = 8;

	/** Sampled from the game's stats interface. */
	private static final Color CELL_BACKGROUND = new Color(62, 53, 41);
	private static final Color PANEL_BACKGROUND = new Color(42, 36, 28);
	private static final Color CELL_BORDER = new Color(28, 24, 18);
	private static final Color LEVEL_TEXT = new Color(255, 255, 0);

	private final SkillIconManager skillIconManager;
	private final List<Skill> order = SkillOrder.panelOrder();
	private final Map<Skill, BufferedImage> iconCache = new EnumMap<>(Skill.class);

	private AccountRecord record;

	StatsGridPanel(SkillIconManager skillIconManager)
	{
		this.skillIconManager = skillIconManager;
		setBackground(PANEL_BACKGROUND);
		setToolTipText("");

		int rows = (int) Math.ceil(order.size() / (double) SkillOrder.COLUMNS);
		setPreferredSize(new Dimension(
			SkillOrder.COLUMNS * CELL_WIDTH + (SkillOrder.COLUMNS + 1) * GAP,
			rows * CELL_HEIGHT + (rows + 1) * GAP + TOTAL_BAR_HEIGHT));
	}

	void setRecord(AccountRecord record)
	{
		this.record = record;
		repaint();
	}

	@Override
	public String getToolTipText(MouseEvent event)
	{
		Skill skill = skillAt(event.getPoint());
		if (skill == null || record == null)
		{
			return null;
		}
		return String.format("%s - level %d, %s xp",
			SkillOrder.prettify(skill.name()),
			record.skillLevel(skill.name()),
			Format.exact(record.skillXpFor(skill.name())));
	}

	private Skill skillAt(Point point)
	{
		int column = (point.x - GAP) / (CELL_WIDTH + GAP);
		int row = (point.y - GAP) / (CELL_HEIGHT + GAP);
		if (column < 0 || column >= SkillOrder.COLUMNS || row < 0)
		{
			return null;
		}
		int index = row * SkillOrder.COLUMNS + column;
		return index >= 0 && index < order.size() ? order.get(index) : null;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			// FRACTIONALMETRICS off pins glyphs to whole pixels - without it,
			// small text at odd cell positions can land on a half-pixel and
			// come out visibly soft. RuneLite's own bold UI font, not a
			// generic Swing default, is what makes this read as "in-game"
			// rather than as a plain Java panel.
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
				RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
				RenderingHints.VALUE_STROKE_PURE);
			g2.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));

			for (int i = 0; i < order.size(); i++)
			{
				int column = i % SkillOrder.COLUMNS;
				int row = i / SkillOrder.COLUMNS;
				int x = GAP + column * (CELL_WIDTH + GAP);
				int y = GAP + row * (CELL_HEIGHT + GAP);
				paintCell(g2, order.get(i), x, y);
			}

			paintTotal(g2);
		}
		finally
		{
			g2.dispose();
		}
	}

	private void paintCell(Graphics2D g2, Skill skill, int x, int y)
	{
		g2.setColor(CELL_BACKGROUND);
		g2.fillRect(x, y, CELL_WIDTH, CELL_HEIGHT);
		g2.setColor(CELL_BORDER);
		g2.drawRect(x, y, CELL_WIDTH, CELL_HEIGHT);

		BufferedImage icon = iconFor(skill);
		if (icon != null)
		{
			g2.drawImage(icon,
				x + 5,
				y + (CELL_HEIGHT - icon.getHeight()) / 2,
				null);
		}

		// An account seen before XP tracking existed, or a skill never
		// reported, reads as level 1 rather than 0 - the game has no level 0.
		int level = record == null ? 1 : Math.max(1, record.skillLevel(skill.name()));
		String text = Integer.toString(level);

		FontMetrics fm = g2.getFontMetrics();
		int textX = x + CELL_WIDTH - TEXT_PADDING - fm.stringWidth(text);
		// Standard single-line vertical centering: half the cap-to-baseline
		// span above the midpoint, then move down by the ascent to land the
		// baseline correctly - centers on the glyphs' visual body, not on
		// the font's full ascent-plus-descent box, which reads as too high.
		int textY = y + (CELL_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;

		g2.setColor(LEVEL_TEXT);
		g2.drawString(text, textX, textY);
	}

	private void paintTotal(Graphics2D g2)
	{
		int rows = (int) Math.ceil(order.size() / (double) SkillOrder.COLUMNS);
		int y = GAP + rows * (CELL_HEIGHT + GAP);
		int width = getWidth() - 2 * GAP;

		g2.setColor(CELL_BACKGROUND);
		g2.fillRect(GAP, y, width, TOTAL_BAR_HEIGHT - GAP);
		g2.setColor(CELL_BORDER);
		g2.drawRect(GAP, y, width, TOTAL_BAR_HEIGHT - GAP);

		String text = "Total level: "
			+ (record == null ? "0" : Format.exact(record.totalLevel()));
		FontMetrics fm = g2.getFontMetrics();
		g2.setColor(LEVEL_TEXT);
		g2.drawString(text,
			GAP + (width - fm.stringWidth(text)) / 2,
			y + (TOTAL_BAR_HEIGHT - GAP + fm.getAscent() - fm.getDescent()) / 2);
	}

	/**
	 * Skill icons are static resources loaded from the client jar, so unlike
	 * item sprites they arrive fully formed and only need caching to avoid
	 * re-decoding them on every repaint.
	 */
	private BufferedImage iconFor(Skill skill)
	{
		if (skillIconManager == null)
		{
			return null;
		}
		BufferedImage cached = iconCache.get(skill);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			BufferedImage icon = skillIconManager.getSkillImage(skill, true);
			if (icon != null)
			{
				iconCache.put(skill, icon);
			}
			return icon;
		}
		catch (RuntimeException e)
		{
			// A skill the installed client has no icon for - draw the cell
			// without one rather than losing the whole panel.
			return null;
		}
	}
}
