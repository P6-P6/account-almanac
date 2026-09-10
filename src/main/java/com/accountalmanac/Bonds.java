package com.accountalmanac;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.runelite.api.gameval.ItemID;

/**
 * Counts Old School Bonds held across every tracked account.
 *
 * <p>Bonds are worth calling out separately from the item table: they are
 * membership rather than ordinary stock, they get scattered one at a time
 * across alts, and it is easy to forget one is sitting in a bank somewhere.
 *
 * <h2>The three ids</h2>
 *
 * <p>A bond exists in more than one form and only counting the tradeable one
 * would undercount:
 *
 * <ul>
 * <li>{@link ItemID#OSRS_BOND} - tradeable, has a Grand Exchange price.</li>
 * <li>{@link ItemID#BOUGHT_OSRS_BOND} - the form held briefly after buying.</li>
 * <li>{@link ItemID#OSRS_BOND_UNTRADEABLE} - what a bond becomes once it has
 * been traded to you or taken out of the exchange. Still a bond, still
 * redeemable for membership, but it has no market price, so it is counted
 * separately rather than valued at the tradeable price.</li>
 * </ul>
 */
final class Bonds
{
	private Bonds()
	{
	}

	/** Where the bonds are and what they are worth. */
	static class Holdings
	{
		int tradeable;
		int untradeable;

		/** Market value of the tradeable ones only. */
		long tradeableValue;

		/** Per-account counts, biggest holder first. */
		final List<AccountHolding> byAccount = new ArrayList<>();

		int total()
		{
			return tradeable + untradeable;
		}

		boolean isEmpty()
		{
			return total() == 0;
		}
	}

	static class AccountHolding
	{
		final AccountRecord record;
		final int count;

		AccountHolding(AccountRecord record, int count)
		{
			this.record = record;
			this.count = count;
		}
	}

	private static boolean isTradeableBond(int itemId)
	{
		return itemId == ItemID.OSRS_BOND || itemId == ItemID.BOUGHT_OSRS_BOND;
	}

	private static boolean isBond(int itemId)
	{
		return isTradeableBond(itemId) || itemId == ItemID.OSRS_BOND_UNTRADEABLE;
	}

	/**
	 * Totals bonds across the supplied accounts.
	 *
	 * <p>Bonds sitting in an active <b>sell</b> offer are counted too. They
	 * have left the bank container, which is why the bank snapshot alone misses
	 * them, but they are still owned - the same reasoning
	 * {@link ItemAggregator} applies to every other item. A <b>buy</b> offer is
	 * excluded: that bond has not been acquired yet.
	 */
	static Holdings count(List<AccountRecord> accounts)
	{
		Holdings holdings = new Holdings();

		for (AccountRecord record : accounts)
		{
			int forAccount = 0;

			for (BankItem item : record.bankItems)
			{
				if (!isBond(item.id) || item.quantity <= 0)
				{
					continue;
				}
				forAccount += item.quantity;
				if (isTradeableBond(item.id))
				{
					holdings.tradeable += item.quantity;
					holdings.tradeableValue += item.totalValue();
				}
				else
				{
					holdings.untradeable += item.quantity;
				}
			}

			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (!offer.isSell() || !offer.isActive() || !isBond(offer.itemId))
				{
					continue;
				}
				int remaining = offer.remainingQuantity();
				if (remaining <= 0)
				{
					continue;
				}
				forAccount += remaining;
				if (isTradeableBond(offer.itemId))
				{
					holdings.tradeable += remaining;
					holdings.tradeableValue += offer.sellStockValue();
				}
				else
				{
					holdings.untradeable += remaining;
				}
			}

			if (forAccount > 0)
			{
				holdings.byAccount.add(new AccountHolding(record, forAccount));
			}
		}

		holdings.byAccount.sort(Comparator.comparingInt((AccountHolding h) -> h.count).reversed());
		return holdings;
	}
}
