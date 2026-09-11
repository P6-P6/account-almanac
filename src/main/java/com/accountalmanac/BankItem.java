package com.accountalmanac;

/**
 * One stack in an account's last-seen bank snapshot.
 *
 * <p>The item name and unit price are captured at snapshot time rather than
 * looked up when rendering. {@code ItemManager} lookups need the client
 * thread and a running client, and the panel has to render banks for
 * accounts that aren't logged in - possibly with no client at all. Storing
 * them denormalised keeps the whole UI layer pure Swing.
 *
 * <p>{@code unitPrice} is therefore the price as of {@code lastUpdated} on
 * the owning {@link AccountRecord}, not the live price. Prices are refreshed
 * in bulk from the client thread; see {@code AccountAlmanacPlugin}.
 */
class BankItem
{
	/** Canonical item id - placeholders and noted variants already resolved. */
	int id;
	int quantity;
	String name = "";
	/** GE price per item at the time this snapshot was taken. */
	int unitPrice;

	/**
	 * High alchemy value per item, captured alongside the price.
	 *
	 * <p>Stored rather than looked up on demand because the lookup needs the
	 * client thread and an item composition, neither of which the viewer has
	 * when it is rendering a bank for an account that is not logged in.
	 */
	int haPrice;

	/**
	 * Whether the item is members-only, from the item composition.
	 *
	 * <p>Boxed on purpose: {@code null} means never captured, which is a
	 * different thing from {@code false} meaning free-to-play. Items stored
	 * before this existed read as null until a price refresh backfills them,
	 * and the filter treats unknown as its own case rather than quietly
	 * counting them as F2P.
	 */
	Boolean members;

	BankItem()
	{
	}

	BankItem(int id, int quantity, String name, int unitPrice, int haPrice)
	{
		this(id, quantity, name, unitPrice, haPrice, null);
	}

	BankItem(int id, int quantity, String name, int unitPrice, int haPrice, Boolean members)
	{
		this.members = members;
		this.id = id;
		this.quantity = quantity;
		this.name = name;
		this.unitPrice = unitPrice;
			this.haPrice = haPrice;
	}

	/** Combined high alchemy value of this stack. */
	long totalHaValue()
	{
		return (long) haPrice * quantity;
	}

	long totalValue()
	{
		return (long) unitPrice * quantity;
	}
}
