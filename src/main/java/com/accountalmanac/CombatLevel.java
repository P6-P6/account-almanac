package com.accountalmanac;

import java.util.Map;

/**
 * The standard OSRS combat level formula, computed from real (unboosted)
 * skill levels. See https://oldschool.runescape.wiki/w/Combat_level.
 */
final class CombatLevel
{
	private CombatLevel()
	{
	}

	static int calculate(Map<String, Integer> skills)
	{
		int attack = skills.getOrDefault("ATTACK", 1);
		int strength = skills.getOrDefault("STRENGTH", 1);
		int defence = skills.getOrDefault("DEFENCE", 1);
		int hitpoints = skills.getOrDefault("HITPOINTS", 10);
		int prayer = skills.getOrDefault("PRAYER", 1);
		int ranged = skills.getOrDefault("RANGED", 1);
		int magic = skills.getOrDefault("MAGIC", 1);

		double base = 0.25 * (defence + hitpoints + Math.floor(prayer / 2.0));
		double melee = 0.325 * (attack + strength);
		double range = 0.325 * Math.floor(3 * ranged / 2.0);
		double mage = 0.325 * Math.floor(3 * magic / 2.0);

		return (int) Math.floor(base + Math.max(melee, Math.max(range, mage)));
	}
}
