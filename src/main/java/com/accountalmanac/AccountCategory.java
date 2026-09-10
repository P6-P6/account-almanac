package com.accountalmanac;

import java.util.ArrayList;
import java.util.List;

/**
 * Suggested values for {@link AccountRecord#category}, the user-assigned
 * grouping for an account.
 *
 * <p>The category is stored on the record as a plain string rather than as
 * this enum, for two reasons: a custom grouping the user types in has to work
 * without a code change, and an unrecognised value read back from an older or
 * hand-edited file has to survive rather than throw. This enum only supplies
 * the presets offered in the dropdown.
 *
 * <p><b>Banned and hidden are deliberately not categories here</b>, even
 * though both were listed alongside these. They are independent flags on the
 * record ({@link AccountRecord#banned}, {@link AccountRecord#hidden}) because
 * they describe state rather than kind: a pure can also be banned, and a main
 * can be hidden from totals while staying a main. Folding them into a
 * single-value category field would force a choice between those and lose
 * information. Both are still offered as groupings in the filter dropdown, so
 * "show me the banned ones" works exactly as expected - see
 * {@link AccountFilter}.
 */
enum AccountCategory
{
	UNSET(""),
	MAIN("Main"),
	IRONMAN("Ironman"),
	PURE("Pure"),
	ZERKER("Zerker"),
	LEVEL_3("Level 3"),
	SKILLER("Skiller"),
	MULE("Mule"),
	OTHER("Other");

	private final String label;

	AccountCategory(String label)
	{
		this.label = label;
	}

	String label()
	{
		return label;
	}

	@Override
	public String toString()
	{
		return label.isEmpty() ? "(none)" : label;
	}

	/** Preset labels for a dropdown, blank first so "no category" is selectable. */
	static List<String> presetLabels()
	{
		List<String> labels = new ArrayList<>();
		for (AccountCategory category : values())
		{
			labels.add(category.label);
		}
		return labels;
	}

	/**
	 * A sensible starting category for an account the user has not classified
	 * yet, inferred from the account type the client reported.
	 *
	 * <p>Only ironman variants are inferred. Whether an account is a pure, a
	 * zerker or a level 3 is a matter of build intent that no API reports -
	 * a level 3 skiller and a fresh account look identical - so those are
	 * left for the user rather than guessed wrongly.
	 */
	static String suggestFor(String accountType)
	{
		if (accountType == null || accountType.isEmpty())
		{
			return UNSET.label;
		}
		String upper = accountType.toUpperCase();
		if (upper.contains("IRONMAN"))
		{
			return IRONMAN.label;
		}
		return UNSET.label;
	}

	/** Trims and collapses a user-entered category, never returning null. */
	static String normalise(String raw)
	{
		return raw == null ? "" : raw.trim();
	}
}
