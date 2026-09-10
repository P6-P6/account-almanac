package com.accountalmanac;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object persisted to {@code history.json}.
 *
 * <p>Kept in its own file rather than inside {@code accounts.json} on purpose.
 * History is append-mostly and grows without bound until pruned, while the
 * account file is the irreplaceable one - forty accounts of identities, banks
 * and labels. Separate files mean a corrupt or oversized history can be
 * discarded on its own without risking the data that actually matters.
 */
class HistoryData
{
	int schemaVersion = 1;

	List<AccountHistory> histories = new ArrayList<>();

	void normalise()
	{
		if (histories == null)
		{
			histories = new ArrayList<>();
			return;
		}
		for (AccountHistory history : histories)
		{
			history.normalise();
		}
	}
}
