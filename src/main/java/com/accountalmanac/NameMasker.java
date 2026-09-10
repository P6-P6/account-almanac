package com.accountalmanac;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns a real account name into whatever the privacy setting says to show.
 *
 * <p>Strictly a display concern. The stored roster, the Grand Exchange event
 * log and the backups all keep real names - {@link AccountRecord#label()} is
 * the persistence-safe accessor and is deliberately left alone, because that is
 * what gets written into logged events. Masking anything on the way to disk
 * would quietly corrupt the user's own records.
 *
 * <p>Mirrors the web viewer's implementation so the two agree on which stand-in
 * belongs to which account.
 */
final class NameMasker
{
	/**
	 * Paired adjective and noun, deliberately RuneScape-flavoured. A stand-in
	 * that reads as a plausible account name makes a shared screenshot look
	 * normal, which is the point - obviously redacted names invite the question
	 * of what was redacted.
	 */
	private static final String[] ADJECTIVES = {
		"Iron", "Swift", "Grim", "Bold", "Runic", "Ancient", "Silent", "Cursed",
		"Golden", "Frozen", "Shadow", "Wild", "Noble", "Rusty", "Sacred", "Crimson",
	};

	private static final String[] NOUNS = {
		"Falcon", "Warden", "Ranger", "Sage", "Reaver", "Scout", "Herald", "Knight",
		"Drake", "Mage", "Archer", "Rogue", "Titan", "Wraith", "Hunter", "Pilgrim",
	};

	/** Assigned stand-ins, so two accounts are never shown as the same person. */
	private static final Map<Long, String> ASSIGNED = new HashMap<>();

	private NameMasker()
	{
	}

	/**
	 * The name to display for an account.
	 *
	 * @param privacy current setting; {@code null} is treated as
	 *                {@link NamePrivacy#REAL} so a missing config never
	 *                accidentally hides everything
	 */
	static String display(AccountRecord record, NamePrivacy privacy)
	{
		if (record == null)
		{
			return "";
		}
		if (privacy == null || !privacy.isMasked())
		{
			return record.label();
		}
		if (privacy == NamePrivacy.HIDDEN)
		{
			return "Account " + shortId(record.accountHash);
		}
		return standInFor(record.accountHash);
	}

	/** Masks a secondary identity field - the login name or the user's label. */
	static String displayField(String value, long accountHash, NamePrivacy privacy)
	{
		if (privacy == null || !privacy.isMasked())
		{
			return value == null ? "" : value;
		}
		// An empty field stays empty; masking a blank would invent an identity
		// that was never there.
		if (value == null || value.isEmpty() || "-".equals(value))
		{
			return value == null ? "" : value;
		}
		if (privacy == NamePrivacy.HIDDEN)
		{
			return "-";
		}
		return standInFor(accountHash);
	}

	/**
	 * A stable stand-in for one account.
	 *
	 * <p>Derived from the account hash rather than assigned in list order, so
	 * the same account keeps the same name across restarts, re-sorts and filter
	 * changes.
	 */
	private static synchronized String standInFor(long accountHash)
	{
		String existing = ASSIGNED.get(accountHash);
		if (existing != null)
		{
			return existing;
		}

		// A display label, not a security boundary, so a cheap stable mix is
		// exactly the right tool.
		int h = Long.hashCode(accountHash);
		h = Math.abs(h == Integer.MIN_VALUE ? 0 : h);

		String name = ADJECTIVES[h % ADJECTIVES.length]
			+ " " + NOUNS[(h / ADJECTIVES.length) % NOUNS.length];

		Set<String> taken = new HashSet<>(ASSIGNED.values());
		if (taken.contains(name))
		{
			int suffix = 2;
			while (taken.contains(name + " " + suffix))
			{
				suffix++;
			}
			name = name + " " + suffix;
		}

		ASSIGNED.put(accountHash, name);
		return name;
	}

	/** Last four digits of the hash - enough to tell accounts apart. */
	private static String shortId(long accountHash)
	{
		String key = Long.toString(Math.abs(accountHash));
		return key.length() <= 4 ? key : key.substring(key.length() - 4);
	}
}
