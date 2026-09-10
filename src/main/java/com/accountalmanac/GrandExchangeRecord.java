package com.accountalmanac;

/**
 * One Grand Exchange slot as of the last time the client reported it.
 *
 * <p>The client fires {@code GrandExchangeOfferChanged} for all eight slots
 * shortly after login, so simply recording every event gives a complete
 * picture of an account's GE state without needing the GE interface opened.
 * That is unlike bank contents, which are only readable while the bank is
 * actually open.
 *
 * <p>{@code state} is the {@code GrandExchangeOfferState} enum name rather
 * than the enum itself so that an unrecognised future state deserialises
 * cleanly instead of throwing.
 */
class GrandExchangeRecord
{
	/** GE slot index, 0-7. */
	int slot;
	int itemId;
	String itemName = "";
	/** GrandExchangeOfferState.name(), e.g. "BUYING", "SOLD", "EMPTY". */
	String state = "EMPTY";
	/** The price per item this offer was listed at - what you asked for, not what it is worth. */
	int pricePerItem;

	/**
	 * Market price per item at the time this offer was last seen or repriced.
	 *
	 * <p>Kept separate from {@link #pricePerItem} because the two answer
	 * different questions, and conflating them badly distorts wealth. A hail
	 * mary listing of 999,000 on an ornament kit worth 3,953 overstated one
	 * account by 1.3 billion; a lowball listing understates just as hard in the
	 * other direction. What you asked for is not what you have.
	 *
	 * <p>0 means never priced - see {@link #sellStockValue()}.
	 */
	int marketPrice;
	int totalQuantity;
	int quantitySold;
	/** Coins already spent (buy) or received (sell) on this offer. */
	long spent;
	long updatedAt;

	boolean isActive()
	{
		return !"EMPTY".equals(state);
	}

	/** True for the buy-side states, including cancelled and completed ones. */
	boolean isBuy()
	{
		return "BUYING".equals(state) || "BOUGHT".equals(state) || "CANCELLED_BUY".equals(state);
	}

	boolean isSell()
	{
		return "SELLING".equals(state) || "SOLD".equals(state) || "CANCELLED_SELL".equals(state);
	}

	/** "Buy" / "Sell" / "" - the column the table groups on. */
	String typeLabel()
	{
		if (isBuy())
		{
			return "Buy";
		}
		if (isSell())
		{
			return "Sell";
		}
		return "";
	}

	/** Human-readable status rather than the raw enum name. */
	String stateLabel()
	{
		switch (state)
		{
			case "BUYING":
				return "Buying";
			case "BOUGHT":
				return "Bought";
			case "SELLING":
				return "Selling";
			case "SOLD":
				return "Sold";
			case "CANCELLED_BUY":
				return "Cancelled buy";
			case "CANCELLED_SELL":
				return "Cancelled sell";
			case "EMPTY":
				return "Empty";
			default:
				return state;
		}
	}

	/** Filled fraction of the offer, 0.0-1.0. */
	double progress()
	{
		if (totalQuantity <= 0)
		{
			return 0.0;
		}
		return Math.min(1.0, (double) quantitySold / (double) totalQuantity);
	}

	int remainingQuantity()
	{
		return Math.max(0, totalQuantity - quantitySold);
	}

	/**
	 * What this offer is currently worth to the account. Returns 0 for an empty
	 * slot.
	 *
	 * <p>The two sides are valued differently on purpose:
	 *
	 * <ul>
	 * <li>A <b>buy</b> offer really has escrowed {@code remaining x listed
	 * price} in coins. That gp is unspendable until the offer fills or is
	 * cancelled, but it is genuinely yours and genuinely that amount, so the
	 * listed price is the correct figure.</li>
	 * <li>A <b>sell</b> offer holds items, not coins. Nobody has agreed to pay
	 * your asking price, so valuing the stock at it is wishful thinking. It is
	 * worth what the same item in your bank is worth - the market price.</li>
	 * </ul>
	 */
	long committedValue()
	{
		if (!isActive())
		{
			return 0L;
		}
		if (isSell())
		{
			return sellStockValue();
		}
		return (long) remainingQuantity() * pricePerItem;
	}

	/**
	 * Market value of the unsold stock sitting in this sell offer.
	 *
	 * <p>Returns 0 rather than falling back to the listed price when the market
	 * price is unknown. Falling back would reintroduce exactly the distortion
	 * this field exists to remove, and understating briefly is recoverable -
	 * any login or a "Refresh prices" fills it in - whereas a bogus billion in
	 * the totals is not obviously wrong until someone goes looking.
	 */
	long sellStockValue()
	{
		if (marketPrice <= 0)
		{
			return 0L;
		}
		return (long) remainingQuantity() * marketPrice;
	}

	/** True when the market price for this offer's item has not been captured yet. */
	boolean needsMarketPrice()
	{
		return isActive() && itemId > 0 && marketPrice <= 0;
	}
}
