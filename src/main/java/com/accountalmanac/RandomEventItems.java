package com.accountalmanac;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The keepsake items the random events hand out, keyed by item id.
 *
 * <p>Ids rather than names, because a name is a display string that Jagex can
 * reword at any time while the id is the thing the game actually stores. The
 * display name is taken from the bank snapshot at render time, so this table
 * only has to say <em>which</em> items count and where each one came from.
 *
 * <p>Only the rewards that survive the event are listed. The events also hand
 * out a great deal of transient junk - puzzle box pieces, broken tool heads,
 * the Quiz Master's wrong answers - and counting those would bury the items
 * anybody actually keeps.
 */
final class RandomEventItems
{
	private RandomEventItems()
	{
	}

	private static final Map<Integer, String> BY_ID = build();

	private static Map<Integer, String> build()
	{
		Map<Integer, String> m = new LinkedHashMap<>();

		// Frog Cave. The token is the trade-in, the rest is what it buys.
		m.put(6183, "Frog Cave");
		m.put(6188, "Frog Cave");
		m.put(6184, "Frog Cave");
		m.put(6185, "Frog Cave");
		m.put(6186, "Frog Cave");
		m.put(6187, "Frog Cave");

		// Lederhosen. Spelled "laderhosen" in the game cache; the id is what
		// matters and the displayed name comes from the snapshot.
		m.put(6180, "Lederhosen");
		m.put(6181, "Lederhosen");
		m.put(6182, "Lederhosen");

		// Mime.
		m.put(3057, "Mime");
		m.put(3058, "Mime");
		m.put(3059, "Mime");
		m.put(3060, "Mime");
		m.put(3061, "Mime");

		// Gravedigger - the zombie outfit. Named DIGGER in the cache.
		m.put(7592, "Gravedigger");
		m.put(7593, "Gravedigger");
		m.put(7594, "Gravedigger");
		m.put(7595, "Gravedigger");
		m.put(7596, "Gravedigger");

		// Drill Demon - the camo outfit.
		m.put(6654, "Drill Demon");
		m.put(6655, "Drill Demon");
		m.put(6656, "Drill Demon");

		// Beekeeper.
		m.put(25129, "Beekeeper");
		m.put(25131, "Beekeeper");
		m.put(25133, "Beekeeper");
		m.put(25135, "Beekeeper");
		m.put(25137, "Beekeeper");

		// Sandwich Lady.
		m.put(23312, "Sandwich Lady");
		m.put(23315, "Sandwich Lady");
		m.put(23318, "Sandwich Lady");

		// One-offs.
		m.put(20590, "Freaky Forester");
		m.put(2528, "Genie");
		m.put(6199, "Quiz Master");

		return Collections.unmodifiableMap(m);
	}

	static boolean isRandomEventItem(int itemId)
	{
		return BY_ID.containsKey(itemId);
	}

	/** Which event drops this item, or empty when it is not one of them. */
	static String sourceOf(int itemId)
	{
		String source = BY_ID.get(itemId);
		return source == null ? "" : source;
	}

	/** How many distinct reward items the table knows about. */
	static int trackedCount()
	{
		return BY_ID.size();
	}

	/**
	 * Whether an item is a burnt cooking failure.
	 *
	 * <p>Matched on the name rather than an id list. Every burnt item the game
	 * has is called "Burnt something", and new ones arrive with every cooking
	 * update - a name test picks those up with no change here, where a
	 * hardcoded id list would silently go stale.
	 */
	static boolean isBurnt(String itemName)
	{
		return itemName != null && itemName.regionMatches(true, 0, "Burnt ", 0, 6);
	}
}
