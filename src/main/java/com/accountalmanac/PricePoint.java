package com.accountalmanac;

/**
 * An item's price at a moment, kept so price movement can be reported without
 * asking anything over the network.
 *
 * <p>{@code ItemManager} only ever exposes the current price - there is no
 * historical lookup - and the web viewer gets its 24 hour comparison from the
 * wiki's API. The plugin deliberately makes no network requests at all, so it
 * earns the same answer by remembering what it saw last time instead.
 */
class PricePoint
{
	int price;

	/** Local PC time this price was recorded, epoch millis. */
	long at;

	PricePoint()
	{
	}

	PricePoint(int price, long at)
	{
		this.price = price;
		this.at = at;
	}
}
