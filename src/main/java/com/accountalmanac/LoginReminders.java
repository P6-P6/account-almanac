package com.accountalmanac;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Accounts that are overdue for a login, longest overdue first.
 *
 * <h2>On the two week mark</h2>
 *
 * <p>The threshold defaults to 14 days because that is where a Grand Exchange
 * offer stops being trustworthy - not because the offer is removed. Offers in
 * Old School RuneScape stay in their slot until they complete or are
 * cancelled; they are not deleted for age, and items and coins sitting in the
 * collection box are held indefinitely. What actually decays is the price: an
 * offer listed a fortnight ago is quoting a fortnight-old market, so a buy may
 * have stopped filling and a sell may be well under what the item is now
 * worth.
 *
 * <p>The reminder is therefore framed as "these need a look", which is true
 * either way, rather than as "these are about to expire", which is not. The
 * threshold is configurable if a different cadence suits better.
 */
final class LoginReminders
{
	private LoginReminders()
	{
	}

	static class Reminder
	{
		final AccountRecord record;
		final long daysSinceLogin;
		final int liveOfferCount;
		final long committedGp;

		Reminder(AccountRecord record, long daysSinceLogin, int liveOfferCount, long committedGp)
		{
			this.record = record;
			this.daysSinceLogin = daysSinceLogin;
			this.liveOfferCount = liveOfferCount;
			this.committedGp = committedGp;
		}

		/** One-line summary for the reminder list, with names masked if configured. */
		String describe(NamePrivacy privacy)
		{
			StringBuilder sb = new StringBuilder();
			sb.append(NameMasker.display(record, privacy))
				.append(" - last login ")
				.append(daysSinceLogin)
				.append(daysSinceLogin == 1 ? " day ago" : " days ago");
			if (liveOfferCount > 0)
			{
				sb.append(", ").append(liveOfferCount)
					.append(liveOfferCount == 1 ? " live GE offer" : " live GE offers")
					.append(" holding ").append(Format.gp(committedGp));
			}
			return sb.toString();
		}
	}

	/**
	 * Accounts overdue for a login.
	 *
	 * <p>Banned accounts are never included. There is nothing to log into, so a
	 * reminder about one is noise by definition - it was briefly an option and
	 * there was no case where turning it on helped.
	 *
	 * @param onlyWithOffers restrict to accounts with outstanding offers
	 */
	static List<Reminder> overdue(
		List<AccountRecord> accounts,
		long now,
		int afterDays,
		boolean onlyWithOffers)
	{
		List<Reminder> due = new ArrayList<>();

		for (AccountRecord record : accounts)
		{
			if (record.banned)
			{
				continue;
			}
			if (record.hidden)
			{
				// Hidden accounts are excluded from totals by the user's own
				// choice; nagging about them would defeat the point.
				continue;
			}

			long days = LoginAge.daysSince(record.lastLoginAt, now);
			if (days < afterDays)
			{
				// Includes "never logged in" (-1). An account seen only at the
				// login screen has no login date to be overdue against.
				continue;
			}

			int liveOffers = 0;
			long committed = 0L;
			for (GrandExchangeRecord offer : record.geOffers)
			{
				if (offer.isActive())
				{
					liveOffers++;
					committed += offer.committedValue();
				}
			}

			if (onlyWithOffers && liveOffers == 0)
			{
				continue;
			}

			due.add(new Reminder(record, days, liveOffers, committed));
		}

		// Longest overdue first, then by how much is tied up in offers, so the
		// most consequential account to log into is at the top.
		due.sort(Comparator
			.comparingLong((Reminder r) -> r.daysSinceLogin).reversed()
			.thenComparing(Comparator.comparingLong((Reminder r) -> r.committedGp).reversed()));
		return due;
	}

}
