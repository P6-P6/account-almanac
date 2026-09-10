package com.accountalmanac;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Groups and filters the account roster for the dropdown above the tables.
 *
 * <p>The options are a mix of two things, deliberately presented as one list
 * because that is how the user thinks about them: real categories they
 * assigned ("Pure", "Mule"), and the two state flags that behave like
 * groupings when you want to browse by them ("Banned", "Hidden"). Keeping
 * banned and hidden as flags on the record rather than as category values is
 * what lets an account be a banned pure, or a hidden main - see
 * {@link AccountCategory} for that reasoning.
 */
final class AccountFilter
{
	/** Everything, including accounts hidden from totals. */
	static final String ALL = "All accounts";

	/** Everything that counts toward totals - the sensible default. */
	static final String ALL_VISIBLE = "All except hidden";

	static final String GROUP_BANNED = "Banned";
	static final String GROUP_HIDDEN = "Hidden";
	static final String GROUP_UNCATEGORISED = "(no category)";

	private AccountFilter()
	{
	}

	/**
	 * Options for the filter dropdown: the two roster-wide choices, then the
	 * state groups that actually apply to at least one account, then every
	 * category in use, alphabetically.
	 *
	 * <p>Empty groups are left out rather than listed and unselectable - an
	 * option that always yields nothing is just noise.
	 */
	static List<String> groupsFor(List<AccountRecord> accounts)
	{
		List<String> groups = new ArrayList<>();
		groups.add(ALL_VISIBLE);
		groups.add(ALL);

		boolean anyBanned = false;
		boolean anyHidden = false;
		boolean anyUncategorised = false;
		Set<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

		for (AccountRecord record : accounts)
		{
			if (record.banned)
			{
				anyBanned = true;
			}
			if (record.hidden)
			{
				anyHidden = true;
			}
			String category = record.categoryLabel();
			if (category.isEmpty())
			{
				anyUncategorised = true;
			}
			else
			{
				categories.add(category);
			}
		}

		if (anyBanned)
		{
			groups.add(GROUP_BANNED);
		}
		if (anyHidden)
		{
			groups.add(GROUP_HIDDEN);
		}
		groups.addAll(categories);
		if (anyUncategorised)
		{
			groups.add(GROUP_UNCATEGORISED);
		}

		return groups;
	}

	/**
	 * Applies a group selection. An unrecognised or null group falls back to
	 * {@link #ALL_VISIBLE} rather than returning nothing, so a category that
	 * disappears (its last account was recategorised) cannot leave the table
	 * mysteriously blank.
	 */
	static List<AccountRecord> apply(List<AccountRecord> accounts, String group)
	{
		if (group == null || ALL_VISIBLE.equals(group))
		{
			return visibleOnly(accounts);
		}
		if (ALL.equals(group))
		{
			return new ArrayList<>(accounts);
		}

		List<AccountRecord> matched = new ArrayList<>();
		for (AccountRecord record : accounts)
		{
			if (matches(record, group))
			{
				matched.add(record);
			}
		}

		if (matched.isEmpty() && !GROUP_BANNED.equals(group) && !GROUP_HIDDEN.equals(group)
			&& !GROUP_UNCATEGORISED.equals(group))
		{
			return visibleOnly(accounts);
		}
		return matched;
	}

	private static boolean matches(AccountRecord record, String group)
	{
		if (GROUP_BANNED.equals(group))
		{
			return record.banned;
		}
		if (GROUP_HIDDEN.equals(group))
		{
			return record.hidden;
		}
		if (GROUP_UNCATEGORISED.equals(group))
		{
			return record.categoryLabel().isEmpty();
		}
		return group.equalsIgnoreCase(record.categoryLabel());
	}

	/**
	 * The accounts that contribute to cross-account totals. This is what every
	 * aggregate view should be built from, so that hiding an account actually
	 * removes it from the numbers rather than only from one table.
	 */
	static List<AccountRecord> visibleOnly(List<AccountRecord> accounts)
	{
		List<AccountRecord> visible = new ArrayList<>(accounts.size());
		for (AccountRecord record : accounts)
		{
			if (record.countsTowardTotals())
			{
				visible.add(record);
			}
		}
		return visible;
	}

	static int hiddenCount(List<AccountRecord> accounts)
	{
		int count = 0;
		for (AccountRecord record : accounts)
		{
			if (record.hidden)
			{
				count++;
			}
		}
		return count;
	}

}
