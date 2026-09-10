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

	BankItem()
	{
	}

	BankItem(int id, int quantity, String name, int unitPrice)
	{
		this.id = id;
		this.quantity = quantity;
		this.name = name;
		this.unitPrice = unitPrice;
	}

	long totalValue()
	{
		return (long) unitPrice * quantity;
	}
}
