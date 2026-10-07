package com.accountalmanac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root object persisted to {@code accounts.json}.
 *
 * <p>Replaces the older {@code VaultData}, which additionally held a KDF
 * salt and password verifier for the removed credential vault. Gson ignores
 * those unknown fields when reading an older file, so existing tracked
 * accounts survive the upgrade - only the encrypted credentials are dropped,
 * and those were unreadable without the master password anyway.
 */
class TrackerData
{
	/** Bumped when the on-disk shape changes in a way that needs migrating. */
	int schemaVersion = 2;

	List<AccountRecord> accounts = new ArrayList<>();

	/**
	 * Accounts the user deleted, as account hash to when it happened.
	 *
	 * <p>Without this a second client still holding the account in memory would
	 * write it straight back on its next save, and a deleted account would keep
	 * reappearing. Entries are forgotten after a month, by which time every
	 * client has long since reloaded.
	 */
	Map<String, Long> removedAccounts = new HashMap<>();

	void normalise()
	{
		if (removedAccounts == null)
		{
			removedAccounts = new HashMap<>();
		}
		if (accounts == null)
		{
			accounts = new ArrayList<>();
			return;
		}
		for (AccountRecord record : accounts)
		{
			record.normalise();
		}
	}
}
