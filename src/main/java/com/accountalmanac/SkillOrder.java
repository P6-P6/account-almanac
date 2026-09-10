package com.accountalmanac;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Skill;

/**
 * The order skills appear in the in-game stats interface.
 *
 * <p>This cannot be taken from {@code Skill.values()}: that enum is in a
 * different order entirely (Attack, Defence, Strength, Hitpoints...) and
 * additionally carries {@code OVERALL}, which is a total rather than a
 * skill and has no cell in the panel. The layout is three columns by eight
 * rows, filled left to right.
 */
final class SkillOrder
{
	static final int COLUMNS = 3;

	/**
	 * Panel order, row by row. Held as enum constants rather than strings so
	 * a skill renamed or removed upstream fails at compile time here instead
	 * of silently vanishing from the grid.
	 */
	private static final Skill[] PANEL_ORDER = {
		Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
		Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
		Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
		Skill.RANGED, Skill.THIEVING, Skill.COOKING,
		Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
		Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
		Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
		Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING,
	};

	private SkillOrder()
	{
	}

	/**
	 * Every skill with a cell in the panel, in display order, followed by
	 * any skill the API knows about that this layout has not been updated
	 * for - so a newly released skill still appears rather than being
	 * silently dropped.
	 */
	@SuppressWarnings("deprecation") // Skill.OVERALL has no replacement; it is deliberately excluded here.
	static List<Skill> panelOrder()
	{
		List<Skill> ordered = new ArrayList<>();
		for (Skill skill : PANEL_ORDER)
		{
			ordered.add(skill);
		}

		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL && !ordered.contains(skill))
			{
				ordered.add(skill);
			}
		}
		return ordered;
	}

	/**
	 * Panel order as {@code Skill.name()} strings - the keys skills are stored
	 * under in {@link AccountRecord#skillXp} and {@link HistorySnapshot#skillXp}.
	 */
	static List<String> names()
	{
		List<Skill> ordered = panelOrder();
		List<String> names = new ArrayList<>(ordered.size());
		for (Skill skill : ordered)
		{
			names.add(skill.name());
		}
		return names;
	}

	/** ATTACK -> Attack, RUNECRAFT -> Runecraft. */
	static String prettify(String skillName)
	{
		if (skillName == null || skillName.isEmpty())
		{
			return "";
		}
		return skillName.charAt(0)
			+ skillName.substring(1).toLowerCase(java.util.Locale.ROOT);
	}
}
