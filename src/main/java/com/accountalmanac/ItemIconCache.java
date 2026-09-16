package com.accountalmanac;

import java.util.HashMap;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Lazily resolves item sprites for table cells.
 *
 * <p>{@link ItemManager#getImage} hands back an {@link AsyncBufferedImage}
 * that is blank until the sprite has been fetched and then fills itself in
 * place, so an {@link ImageIcon} wrapping one can be handed to a renderer
 * immediately - it simply starts empty and becomes correct.
 *
 * <p>The catch is that nothing repaints on its own. Each image gets an
 * {@code onLoaded} callback, and rather than repainting the table once per
 * sprite - hundreds of repaints for a large bank - the callbacks set a flag
 * that a single coalesced {@code repaint()} picks up.
 */
class ItemIconCache
{
	private final ItemManager itemManager;
	private final JComponent repaintTarget;
	/** Keyed by item id and stack size: the sprite changes with the amount. */
	private final Map<Long, ImageIcon> icons = new HashMap<>();

	private boolean repaintPending;

	/**
	 * @param itemManager   may be null, in which case every lookup misses and
	 *                      the tables simply render without sprites
	 * @param repaintTarget component repainted as sprites arrive
	 */
	ItemIconCache(ItemManager itemManager, JComponent repaintTarget)
	{
		this.itemManager = itemManager;
		this.repaintTarget = repaintTarget;
	}

	/**
	 * @return an icon for a single one of the item, possibly still blank, or
	 *         {@code null} if sprites are unavailable
	 */
	Icon get(int itemId)
	{
		return get(itemId, 1L);
	}

	/**
	 * @param quantity how many the row holds, which picks the stack sprite -
	 *                 a pile of coins rather than one coin, a bundle of arrows
	 *                 rather than one arrow
	 * @return an icon for the item, possibly still blank, or {@code null}
	 *         if sprites are unavailable
	 */
	Icon get(int itemId, long quantity)
	{
		if (itemManager == null || itemId <= 0)
		{
			return null;
		}

		// The client takes an int, and the sprite only changes at the game's
		// own stack thresholds, so clamping loses nothing.
		int stack = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, quantity));
		long key = ((long) itemId << 32) | stack;

		ImageIcon cached = icons.get(key);
		if (cached != null)
		{
			return cached;
		}

		// stackable=false: every table carries its own quantity column, so
		// RuneLite must not draw its number over the sprite as well.
		AsyncBufferedImage image = itemManager.getImage(itemId, stack, false);
		ImageIcon icon = new ImageIcon(image);
		icons.put(key, icon);
		image.onLoaded(this::scheduleRepaint);
		return icon;
	}

	/**
	 * Collapses a burst of sprite loads into one repaint. onLoaded fires on
	 * the client thread, so the repaint itself is left to Swing.
	 */
	private synchronized void scheduleRepaint()
	{
		if (repaintPending)
		{
			return;
		}
		repaintPending = true;
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			synchronized (this)
			{
				repaintPending = false;
			}
			repaintTarget.repaint();
		});
	}
}
