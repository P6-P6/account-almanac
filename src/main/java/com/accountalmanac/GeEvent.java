package com.accountalmanac;

/**
 * One logged Grand Exchange event, as written to {@code ge-events.json}.
 *
 * <p>{@link #type} is the {@link GeEventType} name rather than the enum, for
 * the same reason {@link GrandExchangeRecord#state} is - an unrecognised value
 * from a newer version deserialises cleanly instead of throwing and taking the
 * whole log with it.
 */
class GeEvent
{
	/** Local PC time the event was recorded, epoch millis. */
	long at;

	long accountHash;

	/** Account label at the time, so the log stays readable if a name changes. */
	String accountLabel = "";

	/** {@link GeEventType} name. */
	String type = "";

	/** GE slot index 0-7, or -1 when not slot-specific. */
	int slot = -1;

	int itemId;
	String itemName = "";

	/**
	 * Quantity this event concerns: the offer size for a start, the filled
	 * amount for a completion or cancellation, the collected amount for a
	 * collection.
	 */
	int quantity;

	/** The per-item price the offer was listed at. */
	int pricePerItem;

	/**
	 * Coins actually moved by this event: spent for a buy, received for a
	 * sell, collected for a collection. For a start event this is the value
	 * committed to the offer.
	 */
	long totalValue;

	/**
	 * Set when the event was inferred at login rather than witnessed live -
	 * the offer changed while the account was logged out, so the timestamp is
	 * when it was noticed, not when it happened.
	 *
	 * <p>Kept explicit rather than silently pretending the login time is the
	 * trade time, because purchase history is the one place where a wrong
	 * timestamp quietly corrupts the user's own record of what they paid.
	 */
	boolean approximate;

	GeEvent()
	{
	}

	GeEvent(long at, long accountHash, String accountLabel, GeEventType type)
	{
		this.at = at;
		this.accountHash = accountHash;
		this.accountLabel = accountLabel == null ? "" : accountLabel;
		this.type = type.name();
	}

	GeEventType typeOrNull()
	{
		return GeEventType.fromName(type);
	}

	String typeLabel()
	{
		GeEventType parsed = typeOrNull();
		return parsed == null ? type : parsed.label();
	}

	/**
	 * Coins per item actually transacted, which is not always
	 * {@link #pricePerItem}: a buy offer fills at or below the price you
	 * listed, so what you actually paid per item is the total divided by the
	 * count. Falls back to the listed price when nothing was filled.
	 */
	long actualUnitPrice()
	{
		if (quantity <= 0)
		{
			return pricePerItem;
		}
		return totalValue / quantity;
	}

	void normalise()
	{
		if (type == null)
		{
			type = "";
		}
		if (itemName == null)
		{
			itemName = "";
		}
		if (accountLabel == null)
		{
			accountLabel = "";
		}
	}
}
