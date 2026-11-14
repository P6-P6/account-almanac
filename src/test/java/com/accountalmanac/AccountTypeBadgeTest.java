package com.accountalmanac;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AccountTypeBadgeTest
{
	@Test
	public void hardcoreWorldsHasItsOwnLabels()
	{
		assertEquals("HCW", AccountTypeBadge.shortLabel("HARDCORE_MAIN"));
		assertEquals("Hardcore Worlds", AccountTypeBadge.fullLabel("HARDCORE_MAIN"));
		assertTrue(AccountTypeBadge.hasBadge("HARDCORE_MAIN"));
	}

	@Test
	public void hardcoreWorldsUsesRuneLitesHiscoreIcon()
	{
		// Bundled with RuneLite 1.13.6+, which is what the build compiles against.
		assertNotNull(AccountTypeBadge.icon("HARDCORE_MAIN"));
	}

	@Test
	public void hardcoreWorldsIsNotSuggestedAsIronman()
	{
		// A Hardcore Worlds character is a main; its build is the user's call.
		assertEquals("", AccountCategory.suggestFor("HARDCORE_MAIN"));
	}

	@Test
	public void normalAndUnknownStillGetNoBadge()
	{
		assertFalse(AccountTypeBadge.hasBadge("NORMAL"));
		assertFalse(AccountTypeBadge.hasBadge(""));
		assertEquals("-", AccountTypeBadge.fullLabel(""));
	}
}
