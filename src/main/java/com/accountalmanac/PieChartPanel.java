package com.accountalmanac;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A donut chart with an inline legend, drawn directly with Graphics2D.
 *
 * <p>Deliberately not a charting library - AGENTS.md forbids pulling extra
 * dependencies through runelite-client, and a pie with a legend is a couple
 * of {@link Arc2D} calls. This is a sidebar/window component, never an
 * overlay, so the per-frame cost rule for overlays does not apply.
 *
 * <p>By default anything past {@code maxSlices} collapses into one "Other"
 * wedge so a roster of forty accounts still gets a readable pie. A "Show
 * all" toggle underneath switches to the uncapped list instead, scrolling
 * within a fixed-height viewport rather than growing the panel itself - the
 * surrounding layout stays put whichever mode is showing.
 */
class PieChartPanel extends JPanel
{
	private static final int VIEWPORT_HEIGHT = 340;

	private final DonutCanvas canvas;
	private final JButton toggleButton = new JButton();

	private List<ItemAggregator.Slice> fullSlices = Collections.emptyList();
	private int maxSlices = 10;
	private boolean expanded;

	/**
	 * @param valuesAreGp {@code true} to format slice values as coins,
	 *                    {@code false} to show raw quantities
	 */
	PieChartPanel(boolean valuesAreGp)
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		canvas = new DonutCanvas(valuesAreGp);

		JScrollPane scroll = new JScrollPane(canvas);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setPreferredSize(new Dimension(220, VIEWPORT_HEIGHT));
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		toggleButton.setFont(FontManager.getRunescapeSmallFont());
		toggleButton.setVisible(false);
		toggleButton.addActionListener(e ->
		{
			expanded = !expanded;
			applySlices();
		});

		JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
		toggleRow.setOpaque(false);
		toggleRow.add(toggleButton);

		add(scroll, BorderLayout.CENTER);
		add(toggleRow, BorderLayout.SOUTH);
	}

	void setEmptyMessage(String message)
	{
		canvas.setEmptyMessage(message);
	}

	/**
	 * Replaces the chart contents. {@code input} is kept in full - only the
	 * collapsed view discards anything - so toggling "Show all" later never
	 * needs the caller to resupply data.
	 */
	void setSlices(List<ItemAggregator.Slice> input, int maxSlices)
	{
		this.fullSlices = input;
		this.maxSlices = Math.max(1, maxSlices);
		this.expanded = false;
		applySlices();
	}

	private void applySlices()
	{
		boolean hasOverflow = fullSlices.size() > maxSlices;
		toggleButton.setVisible(hasOverflow);
		toggleButton.setText(expanded ? "Show top " + maxSlices : "Show all " + fullSlices.size());

		canvas.setSlices(expanded ? fullSlices : capSlices(fullSlices, maxSlices));
	}

	/** Collapses anything past {@code limit} into one "Other (N)" wedge. */
	private static List<ItemAggregator.Slice> capSlices(List<ItemAggregator.Slice> input, int limit)
	{
		List<ItemAggregator.Slice> trimmed = new ArrayList<>();
		for (int i = 0; i < input.size() && i < limit; i++)
		{
			trimmed.add(input.get(i));
		}

		if (input.size() > limit)
		{
			long other = 0L;
			for (int i = limit; i < input.size(); i++)
			{
				other += input.get(i).value;
			}
			if (other > 0L)
			{
				trimmed.add(new ItemAggregator.Slice(
					"Other (" + (input.size() - limit) + ")", other, 0L));
			}
		}

		return trimmed;
	}

	/** The actual donut + legend painting, unchanged from before apart from living in its own scrollable canvas now. */
	private static class DonutCanvas extends JPanel
	{
		private static final Color[] PALETTE = {
			new Color(219, 166, 76),   // runelite gold
			new Color(88, 150, 214),   // blue
			new Color(106, 176, 106),  // green
			new Color(198, 91, 91),    // red
			new Color(157, 121, 199),  // purple
			new Color(214, 143, 74),   // orange
			new Color(90, 179, 179),   // teal
			new Color(197, 128, 165),  // pink
			new Color(150, 158, 84),   // olive
			new Color(120, 132, 176),  // slate
			new Color(180, 110, 60),   // brown
			new Color(130, 130, 130),  // grey ("Other")
		};

		private static final int DIAMETER = 150;
		private static final int LEGEND_ROW_HEIGHT = 17;
		private static final int SWATCH = 10;
		private static final double DONUT_INNER_RATIO = 0.55;

		private final boolean valuesAreGp;

		private List<ItemAggregator.Slice> slices = Collections.emptyList();
		private long total;
		private String emptyMessage = "No data yet";

		DonutCanvas(boolean valuesAreGp)
		{
			this.valuesAreGp = valuesAreGp;
			setBackground(ColorScheme.DARK_GRAY_COLOR);
			setFont(FontManager.getRunescapeSmallFont());
			// Registers the component with ToolTipManager. The text itself is
			// produced per-position by getToolTipText below; without this call
			// that override is never consulted.
			setToolTipText("");
		}

		/**
		 * Exact figure for whichever slice is under the cursor.
		 *
		 * <p>The chart is painted straight onto one canvas, so there is no
		 * per-slice component to hang a tooltip on - the position has to be
		 * resolved back to a slice by hand. Both the legend rows and the
		 * wedges themselves answer, since either is a natural thing to point
		 * at.
		 *
		 * <p>Worth having because every figure drawn here is abbreviated:
		 * "3.3K" could be anything from 3,250 to 3,349.
		 */
		@Override
		public String getToolTipText(MouseEvent event)
		{
			int index = sliceAt(event.getX(), event.getY());
			if (index < 0 || index >= slices.size())
			{
				return null;
			}

			ItemAggregator.Slice slice = slices.get(index);
			double pct = total > 0L ? 100.0 * slice.value / total : 0.0;
			return String.format("%s: %s%s  (%.2f%%)",
				slice.label,
				Format.exact(slice.value),
				valuesAreGp ? " gp" : "",
				pct);
		}

		/** Index of the slice at a point, or -1. Checks the legend, then the wedges. */
		private int sliceAt(int x, int y)
		{
			if (slices.isEmpty() || total <= 0L)
			{
				return -1;
			}

			int legendTop = DIAMETER + 20 - LEGEND_ROW_HEIGHT;
			if (y >= legendTop)
			{
				int row = (y - legendTop) / LEGEND_ROW_HEIGHT;
				return row >= 0 && row < slices.size() ? row : -1;
			}

			// Inside the ring: convert the offset from centre into an angle and
			// walk the slices the same way they were drawn.
			int cx = getWidth() / 2;
			int cy = 8 + DIAMETER / 2;
			double dx = x - cx;
			double dy = y - cy;
			double radius = Math.sqrt(dx * dx + dy * dy);
			double outer = DIAMETER / 2.0;
			if (radius > outer || radius < outer * DONUT_INNER_RATIO)
			{
				return -1;
			}

			// Painting starts at 90 degrees and sweeps clockwise; screen y grows
			// downward, so the angle is negated to match.
			double degrees = Math.toDegrees(Math.atan2(-dy, dx));
			double swept = 90.0 - degrees;
			if (swept < 0.0)
			{
				swept += 360.0;
			}

			double cursor = 0.0;
			for (int i = 0; i < slices.size(); i++)
			{
				cursor += 360.0 * ((double) slices.get(i).value / (double) total);
				if (swept <= cursor)
				{
					return i;
				}
			}
			return slices.size() - 1;
		}

		void setEmptyMessage(String message)
		{
			this.emptyMessage = message;
		}

		void setSlices(List<ItemAggregator.Slice> slices)
		{
			this.slices = slices;
			long sum = 0L;
			for (ItemAggregator.Slice slice : slices)
			{
				sum += slice.value;
			}
			this.total = sum;

			int height = DIAMETER + 16 + Math.max(1, slices.size()) * LEGEND_ROW_HEIGHT + 8;
			setPreferredSize(new Dimension(200, height));
			revalidate();
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);

			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

				if (slices.isEmpty() || total <= 0L)
				{
					paintEmpty(g2);
					return;
				}

				paintDonut(g2);
				paintLegend(g2);
			}
			finally
			{
				g2.dispose();
			}
		}

		private void paintEmpty(Graphics2D g2)
		{
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			FontMetrics fm = g2.getFontMetrics();
			int x = Math.max(4, (getWidth() - fm.stringWidth(emptyMessage)) / 2);
			g2.drawString(emptyMessage, x, 24);
		}

		private void paintDonut(Graphics2D g2)
		{
			int cx = getWidth() / 2;
			int originX = cx - DIAMETER / 2;
			int originY = 8;

			// Arc2D takes degrees as doubles; accumulating the running start in
			// double and only rounding at draw time avoids the 1-2 degree gap
			// that integer angles leave at the end of the circle.
			double startAngle = 90.0;

			for (int i = 0; i < slices.size(); i++)
			{
				ItemAggregator.Slice slice = slices.get(i);
				double extent = -360.0 * ((double) slice.value / (double) total);

				g2.setColor(colorFor(i, slice));
				g2.fill(new Arc2D.Double(originX, originY, DIAMETER, DIAMETER,
					startAngle, extent, Arc2D.PIE));

				startAngle += extent;
			}

			// Punch the middle out to make it a donut, then ring it so adjacent
			// same-ish colours still read as separate wedges.
			int innerDiameter = (int) (DIAMETER * DONUT_INNER_RATIO);
			int innerOffset = (DIAMETER - innerDiameter) / 2;
			g2.setColor(getBackground());
			g2.fill(new Ellipse2D.Double(originX + innerOffset, originY + innerOffset,
				innerDiameter, innerDiameter));

			g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g2.setStroke(new BasicStroke(1f));
			g2.draw(new Ellipse2D.Double(originX, originY, DIAMETER, DIAMETER));

			// Total in the hole.
			String centre = valuesAreGp ? Format.gp(total) : Format.quantity(total);
			g2.setColor(ColorScheme.BRAND_ORANGE);
			FontMetrics fm = g2.getFontMetrics();
			g2.drawString(centre,
				cx - fm.stringWidth(centre) / 2,
				originY + DIAMETER / 2 + fm.getAscent() / 2 - 2);
		}

		private void paintLegend(Graphics2D g2)
		{
			FontMetrics fm = g2.getFontMetrics();
			int y = DIAMETER + 20;
			int textWidth = getWidth() - (SWATCH + 12);

			for (int i = 0; i < slices.size(); i++)
			{
				ItemAggregator.Slice slice = slices.get(i);
				double pct = 100.0 * slice.value / total;

				g2.setColor(colorFor(i, slice));
				g2.fillRect(4, y - SWATCH + 2, SWATCH, SWATCH);

				String amount = valuesAreGp ? Format.gp(slice.value) : Format.quantity(slice.value);
				String right = String.format("%.1f%%", pct);
				String left = slice.label + "  " + amount;

				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				int rightWidth = fm.stringWidth(right);
				g2.drawString(truncate(fm, left, textWidth - rightWidth - 6), SWATCH + 8, y);

				g2.setColor(ColorScheme.BRAND_ORANGE);
				g2.drawString(right, getWidth() - rightWidth - 4, y);

				y += LEGEND_ROW_HEIGHT;
			}
		}

		/** "Other" always takes the grey at the end of the palette. */
		private Color colorFor(int index, ItemAggregator.Slice slice)
		{
			if (slice.accountHash == 0L && slice.label.startsWith("Other"))
			{
				return PALETTE[PALETTE.length - 1];
			}
			return PALETTE[index % (PALETTE.length - 1)];
		}

		private static String truncate(FontMetrics fm, String text, int maxWidth)
		{
			if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth)
			{
				return text;
			}
			String ellipsis = "...";
			int available = maxWidth - fm.stringWidth(ellipsis);
			if (available <= 0)
			{
				return ellipsis;
			}
			int end = text.length();
			while (end > 0 && fm.stringWidth(text.substring(0, end)) > available)
			{
				end--;
			}
			return text.substring(0, end) + ellipsis;
		}
	}
}
