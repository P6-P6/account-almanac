package com.accountalmanac;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object persisted to {@code ge-events.json}, oldest event first.
 *
 * <p>Separate from both {@code accounts.json} and {@code history.json} for the
 * same reason those are separate from each other: this is the fastest-growing
 * of the three and the least costly to lose.
 */
class GeEventData
{
	int schemaVersion = 1;

	List<GeEvent> events = new ArrayList<>();

	void normalise()
	{
		if (events == null)
		{
			events = new ArrayList<>();
			return;
		}
		for (GeEvent event : events)
		{
			event.normalise();
		}
	}
}
