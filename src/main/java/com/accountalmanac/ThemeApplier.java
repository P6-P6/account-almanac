package com.accountalmanac;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.table.JTableHeader;

/**
 * Paints a {@link ViewerTheme} onto an existing Swing component tree.
 *
 * <p>Done by walking the tree rather than through {@code UIManager}, because
 * {@code UIManager} defaults are global to the JVM: changing them would repaint
 * RuneLite's own windows and every other plugin's panel along with this one.
 * Walking a specific component keeps the change contained to this plugin's own
 * sidebar panel and viewer window, and leaves the rest of the client alone.
 *
 * <p>Two things are deliberately skipped:
 *
 * <ul>
 * <li>Anything whose colour carries meaning - a gain in green, a stale login in
 * red, an item stack above the game's own colour threshold. Those are set by
 * their renderers on every paint, so they survive this untouched, which is what
 * should happen: they encode information, not decoration.</li>
 * <li>{@link StatsGridPanel}, which is a deliberate replica of the in-game
 * stats interface. That panel is brown in the game whatever else is on screen,
 * and recolouring it would make it a worse replica, not a better-themed one.</li>
 * </ul>
 */
final class ThemeApplier
{
	private ThemeApplier()
	{
	}

	static void apply(Component root, ViewerTheme theme)
	{
		if (root == null || theme == null)
		{
			return;
		}
		paint(root, theme);
	}

	private static void paint(Component component, ViewerTheme theme)
	{
		// The in-game stats replica keeps its own palette, and so does anything
		// inside it.
		if (component instanceof StatsGridPanel)
		{
			return;
		}

		if (component instanceof JTable)
		{
			JTable table = (JTable) component;
			table.setBackground(theme.background());
			table.setForeground(theme.text());
			table.setGridColor(theme.grid());
			table.setSelectionBackground(theme.selection());
			table.setSelectionForeground(theme.text());

			JTableHeader header = table.getTableHeader();
			if (header != null)
			{
				header.setBackground(theme.altBackground());
				header.setForeground(theme.dimText());
			}
		}
		else if (component instanceof JTextArea)
		{
			JTextArea area = (JTextArea) component;
			area.setBackground(theme.altBackground());
			area.setForeground(theme.dimText());
		}
		else if (component instanceof JLabel)
		{
			// Only labels still on a theme colour are repainted. One that has
			// been given a meaningful colour - a login age, a reminder count -
			// is left as it is.
			JLabel label = (JLabel) component;
			if (isThemeColour(label))
			{
				label.setForeground(theme.text());
			}
		}
		else if (component instanceof JScrollPane)
		{
			JScrollPane scroll = (JScrollPane) component;
			scroll.setBackground(theme.background());
			JViewport viewport = scroll.getViewport();
			if (viewport != null)
			{
				viewport.setBackground(theme.background());
			}
		}
		else if (component instanceof JTabbedPane || component instanceof JPanel)
		{
			component.setBackground(theme.background());
			component.setForeground(theme.text());
		}

		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				paint(child, theme);
			}
		}
	}

	/**
	 * Whether a label is still wearing a theme colour rather than a meaningful
	 * one. Checked against every theme's palette, not just the active one, so
	 * switching themes repeatedly does not gradually freeze labels at whatever
	 * colour the previous theme left them.
	 */
	private static boolean isThemeColour(JLabel label)
	{
		java.awt.Color current = label.getForeground();
		if (current == null)
		{
			return true;
		}
		for (ViewerTheme candidate : ViewerTheme.values())
		{
			if (current.equals(candidate.text())
				|| current.equals(candidate.dimText())
				|| current.equals(candidate.accent()))
			{
				return true;
			}
		}
		return false;
	}
}
