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
	private final Map<Integer, ImageIcon> icons = new HashMap<>();

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
	 * @return an icon for the item, possibly still blank, or {@code null}
	 *         if sprites are unavailable
	 */
	Icon get(int itemId)
	{
		if (itemManager == null || itemId <= 0)
		{
			return null;
		}

		ImageIcon cached = icons.get(itemId);
		if (cached != null)
		{
			return cached;
		}

		AsyncBufferedImage image = itemManager.getImage(itemId);
		ImageIcon icon = new ImageIcon(image);
		icons.put(itemId, icon);
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
