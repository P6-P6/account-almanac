package com.accountalmanac;

import java.util.ArrayList;
import java.util.List;

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

	void normalise()
	{
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
