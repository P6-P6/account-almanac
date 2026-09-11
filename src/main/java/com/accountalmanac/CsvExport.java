package com.accountalmanac;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Writes the plugin's tables out as CSV.
 *
 * <p>Raw values throughout: unformatted numbers, epoch millis for times, item
 * ids alongside names. A CSV exists to be computed on, and every formatting
 * decision taken here is one the reader cannot undo - a spreadsheet can render
 * 1200000 however it likes, but it cannot recover the number from "1.2M".
 *
 * <p>Each export is one streaming pass over records already in memory. Nothing
 * is collected into an intermediate list first, so the cost is the file write.
 */
final class CsvExport
{
	private CsvExport()
	{
	}

	/**
	 * Every distinct item held across the supplied accounts.
	 *
	 * @return how many rows were written
	 */
	static int items(File file, List<AccountRecord> accounts) throws IOException
	{
		List<ItemAggregator.ItemTotal> totals = ItemAggregator.aggregate(accounts);
		try (Csv csv = Csv.open(file,
			"item_id", "item", "members", "quantity", "unit_price",
			"total_value", "accounts_holding"))
		{
			for (ItemAggregator.ItemTotal total : totals)
			{
				csv.row(total.itemId, total.name, total.members,
					total.totalQuantity, total.unitPrice, total.totalValue,
					total.accountCount());
			}
			return totals.size();
		}
	}

	/** One row per account: identity, levels and wealth. */
	static int accounts(File file, List<AccountRecord> accounts, NamePrivacy privacy)
		throws IOException
	{
		try (Csv csv = Csv.open(file,
			"account", "login_name", "label", "group", "type", "hidden", "banned",
			"combat", "total_level", "total_xp", "quest_points", "quest_points_max",
			"playtime_minutes", "bank_value", "ge_value", "total_wealth",
			"bank_items", "last_login_millis", "bank_seen_millis"))
		{
			for (AccountRecord a : accounts)
			{
				csv.row(
					NameMasker.display(a, privacy),
					NameMasker.displayField(a.loginName, a.accountHash, privacy),
					NameMasker.displayField(a.loginLabel, a.accountHash, privacy),
					a.categoryLabel(),
					AccountTypeBadge.fullLabel(a.accountType),
					a.hidden, a.banned,
					a.combatLevel, a.totalLevel(), a.totalXp(),
					// Blank rather than zero when never captured: an account
					// with no quest points and one never read are different.
					a.hasQuestPoints() ? a.questPoints : null,
					a.hasQuestPoints() ? a.questPointsMax : null,
					a.playtimeMinutes > 0 ? a.playtimeMinutes : null,
					a.bankValue, a.geValue(), a.totalWealth(),
					a.bankItems.size(),
					a.lastLoginAt > 0 ? a.lastLoginAt : null,
					a.hasBankSnapshot() ? a.lastSnapshotAt : null);
			}
			return accounts.size();
		}
	}

	/** Every account's bank, one row per stack, so it can be pivoted. */
	static int banks(File file, List<AccountRecord> accounts, NamePrivacy privacy)
		throws IOException
	{
		int rows = 0;
		try (Csv csv = Csv.open(file,
			"account", "item_id", "item", "members", "quantity",
			"unit_price", "total_value", "alch_price", "bank_seen_millis"))
		{
			for (AccountRecord a : accounts)
			{
				String name = NameMasker.display(a, privacy);
				for (BankItem item : a.bankItems)
				{
					csv.row(name, item.id, item.name, item.members, item.quantity,
						item.unitPrice, item.totalValue(), item.haPrice,
						a.hasBankSnapshot() ? a.lastSnapshotAt : null);
					rows++;
				}
			}
		}
		return rows;
	}

	/** Open Grand Exchange slots, with how far each sits from market price. */
	static int offers(File file, List<AccountRecord> accounts, NamePrivacy privacy)
		throws IOException
	{
		int rows = 0;
		try (Csv csv = Csv.open(file,
			"account", "slot", "type", "state", "item_id", "item",
			"quantity_traded", "quantity_total", "progress", "unit_price",
			"market_price", "vs_market", "spent", "committed"))
		{
			for (AccountRecord a : accounts)
			{
				String name = NameMasker.display(a, privacy);
				for (GrandExchangeRecord o : a.geOffers)
				{
					if (!o.isActive())
					{
						continue;
					}
					csv.row(name, o.slot + 1, o.typeLabel(), o.state, o.itemId, o.itemName,
						o.quantitySold, o.totalQuantity,
						// A fraction, not a percentage string, so it can be
						// charted or averaged without being parsed back.
						round(o.progress()),
						o.pricePerItem,
						o.marketPrice > 0 ? o.marketPrice : null,
						o.priceVsMarket() == null ? null : round(o.priceVsMarket()),
						o.spent, o.committedValue());
					rows++;
				}
			}
		}
		return rows;
	}

	/**
	 * Wealth and experience over time, one row per snapshot.
	 *
	 * <p>This is the export that makes the history chartable outside the
	 * plugin, which is the whole reason it is worth having.
	 */
	static int history(File file, List<AccountRecord> accounts, HistoryStore store,
		NamePrivacy privacy) throws IOException
	{
		int rows = 0;
		try (Csv csv = Csv.open(file,
			"account", "at_millis", "bank_value", "ge_value", "total_wealth",
			"total_xp", "total_level", "combat_level"))
		{
			for (AccountRecord a : accounts)
			{
				AccountHistory history = store.historyFor(a.accountHash);
				if (history == null)
				{
					continue;
				}
				String name = NameMasker.display(a, privacy);
				for (HistorySnapshot s : history.snapshots)
				{
					csv.row(name, s.at, s.bankValue, s.geValue, s.totalWealth(),
						s.totalXp, s.totalLevel, s.combatLevel);
					rows++;
				}
			}
		}
		return rows;
	}

	/** Per-skill experience for every account, long form for pivoting. */
	static int skills(File file, List<AccountRecord> accounts, NamePrivacy privacy)
		throws IOException
	{
		int rows = 0;
		try (Csv csv = Csv.open(file, "account", "skill", "level", "xp"))
		{
			for (AccountRecord a : accounts)
			{
				String name = NameMasker.display(a, privacy);
				for (Map.Entry<String, Integer> entry : a.skillXp.entrySet())
				{
					Integer level = a.skillLevels.get(entry.getKey());
					csv.row(name, entry.getKey(), level, entry.getValue());
					rows++;
				}
			}
		}
		return rows;
	}

	/** Four decimals is past anything the source data justifies. */
	private static double round(double v)
	{
		return Math.round(v * 10000.0) / 10000.0;
	}
}
