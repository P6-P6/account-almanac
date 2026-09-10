package com.accountalmanac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	/**
	 * Item id to the price it was last compared against, keyed as a string
	 * because that is how Gson writes map keys anyway.
	 *
	 * <p>This is the baseline price movement is measured from. It rolls
	 * forward once it is older than the comparison window, so it always
	 * represents roughly one window ago rather than drifting to whenever the
	 * plugin first ran.
	 */
	Map<String, PricePoint> priceBaselines = new HashMap<>();

	void normalise()
	{
		if (priceBaselines == null)
		{
			priceBaselines = new HashMap<>();
		}
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
