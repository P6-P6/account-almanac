package com.accountalmanac;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolls every account's bank snapshot up into a single cross-account view:
 * one {@link ItemTotal} per distinct item, carrying the per-account split
 * that the pie charts render.
 *
 * <p>Pure computation over already-loaded records - no client access, no IO -
 * so it is safe to call straight from the Swing thread.
 */
final class ItemAggregator
{
	private ItemAggregator()
	{
	}

	/**
	 * One item summed across every account that holds it.
	 */
	static class ItemTotal
	{
		final int itemId;
		final String name;
		long totalQuantity;
		long totalValue;
		int unitPrice;

		/** Account hash to quantity held, only for accounts holding it. */
		final Map<Long, Long> byAccount = new LinkedHashMap<>();

		ItemTotal(int itemId, String name)
		{
			this.itemId = itemId;
			this.name = name;
		}

		/**
		 * Share of this item's total quantity held by one account, 0.0-1.0.
		 * Returns 0 when the total is zero, rather than dividing by it.
		 */
		double shareOf(long accountHash)
		{
			if (totalQuantity <= 0L)
			{
				return 0.0;
			}
			Long held = byAccount.get(accountHash);
			return held == null ? 0.0 : (double) held / (double) totalQuantity;
		}

		int accountCount()
		{
			return byAccount.size();
		}
	}

	/**
	 * Aggregates every item across the supplied accounts, ordered by total
	 * value descending - the order the "all items" table renders in.
	 *
	 * <p>Accounts with no bank snapshot contribute nothing; they are not an
	 * empty bank, just an unseen one.
	 *
	 * <p>Also folds in the unsold remainder of every active <b>sell</b>
	 * offer - those items have left the bank container entirely (that is
	 * why the bank event does not see them), but they are still the
	 * account's items, just parked in GE escrow rather than sold. A
	 * <b>buy</b> offer is the opposite case and is deliberately excluded:
	 * the coins are already counted via {@link AccountRecord#geValue()},
	 * but the item itself has not been acquired yet, so counting it here
	 * would claim ownership of something not yet owned.
	 */
	static List<ItemTotal> aggregate(List<AccountRecord> accounts)
	{
		Map<Integer, ItemTotal> byId = new LinkedHashMap<>();

		for (AccountRecord record : accounts)
		{
			for (BankItem item : record.bankItems)
			{
				if (item.quantity <= 0)
				{
					continue;
				}

				ItemTotal total = byId.computeIfAbsent(item.id,
					id -> new ItemTotal(id, item.name == null ? "" : item.name));

				total.totalQuantity += item.quantity;
				total.totalValue += item.totalValue();
				// Snapshots are taken at different times, so unit prices can
				// disagree between accounts. The most recently seen price
				// wins, which is what "Refresh prices" converges everything to.
				total.unitPrice = item.unitPrice;
				total.byAccount.merge(record.accountHash, (long) item.quantity, Long::sum);
			}

			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (!offer.isSell() || !offer.isActive())
				{
					continue;
				}
				int remaining = offer.remainingQuantity();
				if (remaining <= 0)
				{
					continue;
				}

				ItemTotal total = byId.computeIfAbsent(offer.itemId,
					id -> new ItemTotal(id, offer.itemName == null ? "" : offer.itemName));

				// Valued at the market price, never the asking price. Using the
				// listed price here did two kinds of damage: it inflated the
				// total for anything listed optimistically, and - because it
				// also overwrote unitPrice below - it repriced every bank copy
				// of the same item to match the listing.
				int unit = offer.marketPrice > 0 ? offer.marketPrice : total.unitPrice;

				total.totalQuantity += remaining;
				total.totalValue += (long) remaining * unit;
				if (unit > 0)
				{
					total.unitPrice = unit;
				}
				total.byAccount.merge(record.accountHash, (long) remaining, Long::sum);
			}
		}

		List<ItemTotal> totals = new ArrayList<>(byId.values());
		totals.sort(Comparator
			.comparingLong((ItemTotal t) -> t.totalValue).reversed()
			.thenComparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
		return totals;
	}

	/**
	 * Total wealth per account - bank plus GE escrow - ordered richest
	 * first. Drives the "wealth split across accounts" pie.
	 */
	static List<Slice> wealthByAccount(List<AccountRecord> accounts,
		java.util.function.Function<AccountRecord, String> nameOf)
	{
		List<Slice> slices = new ArrayList<>();
		for (AccountRecord record : accounts)
		{
			long wealth = record.totalWealth();
			if (wealth > 0L)
			{
				// The caller supplies the name so the privacy setting applies.
				// This used record.label() directly, which is the deliberately
				// unmasked persistence accessor - so with masking on, the wealth
				// pie's legend still listed every real character name.
				slices.add(new Slice(nameOf.apply(record), wealth, record.accountHash));
			}
		}
		slices.sort(Comparator.comparingLong((Slice s) -> s.value).reversed());
		return slices;
	}

	/**
	 * Per-account split of one item, ordered by quantity held descending.
	 * Drives the "what percent of this item is on what account" pie.
	 */
	static List<Slice> splitForItem(ItemTotal total, Map<Long, String> accountLabels)
	{
		List<Slice> slices = new ArrayList<>();
		for (Map.Entry<Long, Long> entry : total.byAccount.entrySet())
		{
			String label = accountLabels.getOrDefault(entry.getKey(),
				"Account " + Long.toHexString(entry.getKey()));
			slices.add(new Slice(label, entry.getValue(), entry.getKey()));
		}
		slices.sort(Comparator.comparingLong((Slice s) -> s.value).reversed());
		return slices;
	}

	/** Grand total of every account's bank plus GE holdings. */
	static long totalWealth(List<AccountRecord> accounts)
	{
		long total = 0L;
		for (AccountRecord record : accounts)
		{
			total += record.totalWealth();
		}
		return total;
	}

	/**
	 * A single wedge: a label, a magnitude, and the account it belongs to.
	 */
	static class Slice
	{
		final String label;
		final long value;
		final long accountHash;

		Slice(String label, long value, long accountHash)
		{
			this.label = label;
			this.value = value;
			this.accountHash = accountHash;
		}
	}
}
