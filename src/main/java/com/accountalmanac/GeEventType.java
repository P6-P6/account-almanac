package com.accountalmanac;

/**
 * A single thing that happened to a Grand Exchange slot.
 *
 * <p>These are derived from transitions between {@code GrandExchangeOfferState}
 * values rather than reported directly - the client tells you what a slot
 * <em>is</em>, never what just <em>changed</em>, so the tracker keeps the
 * previous state per slot and diffs it. See {@link GeEventTracker}.
 */
enum GeEventType
{
	BUY_STARTED("Started buy offer", false),
	BUY_COMPLETED("Finished buy offer", false),
	BUY_CANCELLED("Cancelled buy offer", false),

	SELL_STARTED("Started sell offer", true),
	SELL_COMPLETED("Finished sell offer", true),
	SELL_CANCELLED("Cancelled sell offer", true),

	/**
	 * Items taken out of the collection box. Applies to both sides: a buy
	 * yields the items bought, a cancelled sell returns the unsold stock.
	 */
	COLLECTED_ITEMS("Collected items", false),

	/**
	 * Coins taken out of the collection box. A completed sell yields the sale
	 * proceeds; a buy can also yield coins back, either because it filled
	 * below the price offered or because it was cancelled with escrow left.
	 */
	COLLECTED_COINS("Collected coins", false);

	private final String label;
	private final boolean sellSide;

	GeEventType(String label, boolean sellSide)
	{
		this.label = label;
		this.sellSide = sellSide;
	}

	String label()
	{
		return label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	boolean isSellSide()
	{
		return sellSide;
	}

	/**
	 * True for anything that moved goods or coins, completed or cancelled.
	 * A partially-filled offer that was then cancelled still bought or sold
	 * something, so it belongs in purchase/sale history too.
	 */
	boolean isTradeOutcome()
	{
		return this == BUY_COMPLETED || this == SELL_COMPLETED
			|| this == BUY_CANCELLED || this == SELL_CANCELLED;
	}

	/** Parses a stored name, returning {@code null} for an unknown value. */
	static GeEventType fromName(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (GeEventType type : values())
		{
			if (type.name().equals(name))
			{
				return type;
			}
		}
		return null;
	}
}
