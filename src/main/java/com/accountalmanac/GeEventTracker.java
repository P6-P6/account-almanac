package com.accountalmanac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns Grand Exchange slot state changes into a log of discrete events.
 *
 * <p>The client only ever reports what a slot <em>is</em>. To know that a buy
 * offer just started, or that coins were just collected, you have to remember
 * what the slot was last time and diff it. The previous state is the one
 * already persisted on {@link AccountRecord#geOffers}, so the diff survives a
 * client restart for free - no extra bookkeeping file.
 *
 * <p>Pure computation, no client access and no IO, so it is safe to call from
 * the client thread where the offer event arrives.
 *
 * <h2>Why some transitions produce no event</h2>
 *
 * <p><b>First sight of a slot</b> logs nothing. On the very first run every
 * account's existing offers would otherwise appear as "started offer" at the
 * moment the tracker happened to notice them, stamping today's date onto
 * trades that may be weeks old. Purchase history is exactly the place where a
 * confidently wrong timestamp is worse than a missing row, so the slot is
 * seeded silently and logging begins from the next real change.
 *
 * <p><b>Partial fills</b> log nothing. An offer sitting in {@code BUYING}
 * while its filled count creeps up would emit an event per tick of progress,
 * and the completion event already carries the true average price paid via
 * {@link GeEvent#actualUnitPrice()}. Progress is still visible live on the
 * offer row itself.
 */
final class GeEventTracker
{
	private GeEventTracker()
	{
	}

	/**
	 * @param prev        last known state of this slot, or {@code null} if the
	 *                    slot has never been seen for this account
	 * @param next        state just reported by the client
	 * @param approximate {@code true} when this change was noticed during the
	 *                    post-login state sync, meaning it may have actually
	 *                    happened earlier (offline, or in another client)
	 * @return events to append, oldest first; empty if nothing notable changed
	 */
	static List<GeEvent> diff(
		GrandExchangeRecord prev,
		GrandExchangeRecord next,
		long accountHash,
		String accountLabel,
		long now,
		boolean approximate)
	{
		if (next == null)
		{
			return Collections.emptyList();
		}

		// First sight of this slot - seed, do not invent history. See class docs.
		if (prev == null)
		{
			return Collections.emptyList();
		}

		// The login burst re-reports every slot verbatim. Identical state is by
		// far the common case and must stay silent, or every login would
		// duplicate the entire log.
		if (unchanged(prev, next))
		{
			return Collections.emptyList();
		}

		List<GeEvent> events = new ArrayList<>(2);
		String prevState = prev.state == null ? "EMPTY" : prev.state;
		String nextState = next.state == null ? "EMPTY" : next.state;

		if (prevState.equals(nextState))
		{
			// Same state but a different item means the slot turned over
			// entirely while unobserved - the old offer finished, was collected,
			// and a new one was placed, all between two sightings. Reporting
			// nothing would drop a completed trade from purchase history, which
			// cannot be reconstructed later.
			if (prev.itemId != next.itemId && prev.itemId > 0 && next.itemId > 0)
			{
				events.addAll(collection(prev, accountHash, accountLabel, now, true));
				events.add(start(
					"SELLING".equals(nextState) ? GeEventType.SELL_STARTED : GeEventType.BUY_STARTED,
					next, accountHash, accountLabel, now, approximate));
				return events;
			}

			// Same state, same item, different numbers: a partial fill.
			// Deliberately silent - see the class docs.
			return Collections.emptyList();
		}

		switch (nextState)
		{
			case "BUYING":
			case "SELLING":
				if (isIdle(prevState))
				{
					// A finished offer being replaced in one step means it was
					// collected to free the slot, even though the slot never
					// passed through EMPTY where that is normally detected. The
					// two paths would otherwise disagree about the same real
					// event, and the collection would be lost for good.
					if (!"EMPTY".equals(prevState))
					{
						events.addAll(collection(prev, accountHash, accountLabel, now, true));
					}
					events.add(start(
						"SELLING".equals(nextState) ? GeEventType.SELL_STARTED : GeEventType.BUY_STARTED,
						next, accountHash, accountLabel, now, approximate));
				}
				break;

			case "BOUGHT":
				events.add(outcome(GeEventType.BUY_COMPLETED, next, accountHash, accountLabel, now, approximate));
				break;

			case "SOLD":
				events.add(outcome(GeEventType.SELL_COMPLETED, next, accountHash, accountLabel, now, approximate));
				break;

			case "CANCELLED_BUY":
				events.add(outcome(GeEventType.BUY_CANCELLED, next, accountHash, accountLabel, now, approximate));
				break;

			case "CANCELLED_SELL":
				events.add(outcome(GeEventType.SELL_CANCELLED, next, accountHash, accountLabel, now, approximate));
				break;

			case "EMPTY":
				// The slot was cleared, which only happens by collecting. What
				// came out depends on which side the finished offer was on, and
				// is described entirely by the state we are leaving behind.
				events.addAll(collection(prev, accountHash, accountLabel, now, approximate));
				break;

			default:
				break;
		}

		return events;
	}

	/**
	 * Nothing worth logging changed. Compared field by field rather than by
	 * timestamp, because the login burst rewrites {@code updatedAt} on every
	 * slot even when the offer itself is untouched.
	 */
	private static boolean unchanged(GrandExchangeRecord a, GrandExchangeRecord b)
	{
		return sameState(a, b)
			&& a.itemId == b.itemId
			&& a.totalQuantity == b.totalQuantity
			&& a.quantitySold == b.quantitySold
			&& a.spent == b.spent
			&& a.pricePerItem == b.pricePerItem;
	}

	private static boolean sameState(GrandExchangeRecord a, GrandExchangeRecord b)
	{
		String left = a.state == null ? "EMPTY" : a.state;
		String right = b.state == null ? "EMPTY" : b.state;
		return left.equals(right);
	}

	/**
	 * A slot that is not currently running an offer. Reached either from empty
	 * or straight from a collected previous offer, since the client can report
	 * a new offer in a slot the same tick the old one was cleared.
	 */
	private static boolean isIdle(String state)
	{
		return "EMPTY".equals(state)
			|| "BOUGHT".equals(state)
			|| "SOLD".equals(state)
			|| "CANCELLED_BUY".equals(state)
			|| "CANCELLED_SELL".equals(state);
	}

	/** An offer being placed: quantity is the full offer, value is what it commits. */
	private static GeEvent start(
		GeEventType type, GrandExchangeRecord offer,
		long accountHash, String accountLabel, long now, boolean approximate)
	{
		GeEvent event = base(type, offer, accountHash, accountLabel, now, approximate);
		event.quantity = offer.totalQuantity;
		event.totalValue = (long) offer.pricePerItem * offer.totalQuantity;
		return event;
	}

	/**
	 * An offer finishing or being cancelled: quantity is what actually filled
	 * and value is the coins that actually moved, so a cancelled offer that
	 * half-filled still records the half it traded.
	 */
	private static GeEvent outcome(
		GeEventType type, GrandExchangeRecord offer,
		long accountHash, String accountLabel, long now, boolean approximate)
	{
		GeEvent event = base(type, offer, accountHash, accountLabel, now, approximate);
		event.quantity = offer.quantitySold;
		event.totalValue = offer.spent;
		return event;
	}

	/**
	 * What came out of the collection box, derived from the offer that was
	 * sitting in the slot.
	 *
	 * <p>Both sides can yield both kinds of thing, which is why this returns a
	 * list rather than one event:
	 *
	 * <ul>
	 * <li>A completed <b>buy</b> hands over the items bought. It also hands
	 * back coins whenever the offer filled below the price listed - the
	 * difference between the escrow taken up front and the amount actually
	 * spent - and that difference is the whole escrow remainder if the offer
	 * was cancelled part-filled.</li>
	 * <li>A completed <b>sell</b> hands over the proceeds. A cancelled sell
	 * also hands back whatever stock never sold.</li>
	 * </ul>
	 */
	private static List<GeEvent> collection(
		GrandExchangeRecord prev,
		long accountHash, String accountLabel, long now, boolean approximate)
	{
		List<GeEvent> events = new ArrayList<>(2);
		boolean buySide = prev.isBuy();
		boolean sellSide = prev.isSell();

		if (!buySide && !sellSide)
		{
			// Slot went empty from empty. Nothing was collected.
			return events;
		}

		if (buySide)
		{
			if (prev.quantitySold > 0)
			{
				GeEvent items = base(GeEventType.COLLECTED_ITEMS, prev, accountHash, accountLabel, now, approximate);
				items.quantity = prev.quantitySold;
				items.totalValue = prev.spent;
				events.add(items);
			}

			// Coins escrowed up front, minus what was actually spent.
			long escrow = (long) prev.pricePerItem * prev.totalQuantity;
			long refund = escrow - prev.spent;
			if (refund > 0L)
			{
				GeEvent coins = base(GeEventType.COLLECTED_COINS, prev, accountHash, accountLabel, now, approximate);
				coins.quantity = 0;
				coins.totalValue = refund;
				events.add(coins);
			}
		}
		else
		{
			if (prev.spent > 0L)
			{
				GeEvent coins = base(GeEventType.COLLECTED_COINS, prev, accountHash, accountLabel, now, approximate);
				coins.quantity = 0;
				coins.totalValue = prev.spent;
				events.add(coins);
			}

			int unsold = prev.remainingQuantity();
			if (unsold > 0)
			{
				GeEvent items = base(GeEventType.COLLECTED_ITEMS, prev, accountHash, accountLabel, now, approximate);
				items.quantity = unsold;
				// Market value, not the asking price. Stock coming back from a
				// cancelled sell is worth what it is worth - valuing it at the
				// listing is the same mistake that had an ornament kit listed at
				// 999,000 claiming that much had been collected.
				items.totalValue = prev.sellStockValue();
				events.add(items);
			}
		}

		return events;
	}

	private static GeEvent base(
		GeEventType type, GrandExchangeRecord offer,
		long accountHash, String accountLabel, long now, boolean approximate)
	{
		GeEvent event = new GeEvent(now, accountHash, accountLabel, type);
		event.slot = offer.slot;
		event.itemId = offer.itemId;
		event.itemName = offer.itemName == null ? "" : offer.itemName;
		event.pricePerItem = offer.pricePerItem;
		event.approximate = approximate;
		return event;
	}
}
