# Account Almanac

A complete record of every account you play, in one place.

Log into an account through RuneLite and Account Almanac remembers it: its
bank, its Grand Exchange offers, its skills, its combat level and what it is
all worth. Log into another and it remembers that one too. Nothing is sent
anywhere - everything is written to files inside your own `.runelite`
directory.

It exists because the game gives you no way to answer "how much do I have,
in total, across everything I own", and no way to remember what an alt's bank
looked like without logging into it.

## What it tracks

**Per account** - bank contents item by item, all eight Grand Exchange slots,
every skill's level and experience, combat level, account type, and the exact
local date and time you last logged in.

**Across every account** - total wealth, every item summed with a per-account
breakdown, total experience per skill, combined total level, and how many
bonds you are holding and where.

**Over time** - snapshots are kept so wealth and experience can be compared
over the past 7 days, month, 90 days or year. A window your history does not
reach back across still reports real figures, measured from the oldest
snapshot available, and says so rather than pretending otherwise.

**Grand Exchange history** - offers started, finished, cancelled and
collected, with what was actually paid or received per item. That is not
always the price you listed at: a buy fills at or below your offer, so both
are recorded. A per-item rollup shows what you have really paid for something
across every offer and every account.

## Views

A sidebar panel gives the summary: combined wealth, the most recent login,
anything overdue, and one row per account. Everything wider than that lives in
a separate window, opened from the panel:

| Tab | |
|---|---|
| Accounts | every account side by side, sortable on any column |
| All items | every item summed, with a per-account pie for the selected one |
| Grand Exchange | every outstanding offer |
| GE log | events, purchases, sales, and a per-item cost basis |
| Stats | the in-game stats interface rebuilt, plus a cross-account matrix |
| Wealth split | who holds what share of the total |
| Wealth history | dated snapshots, per account or combined |
| Interesting | roster-wide totals and things worth noticing |
| Settings | themes, number formats, colours, thresholds, backups |

## Privacy

Account names can be masked for screenshots and streaming: shown as they are,
replaced with stable stand-in names, or hidden entirely. This is display only
- the stored files always keep the real names, so switching back restores them
immediately, and nothing is lost by turning it on.

Only the Jagex launcher's account name is recorded. The client's own login
field is deliberately never read, because on the classic login flow it holds
whatever was typed to sign in, which is frequently an email address.

## Where your data lives

Three files under `.runelite/accountalmanac/`:

| file | |
|---|---|
| `accounts.json` | the roster: identities, banks, offers, skills |
| `history.json` | snapshots over time |
| `ge-events.json` | the Grand Exchange log |

They are written atomically - to a temporary file, then moved into place - so
an interrupted write cannot leave a half-saved file behind. Backups are copied
to `.runelite/accountalmanac/backups/` on a schedule you set, keeping the most
recent few.

Nothing leaves your machine. The plugin makes no network requests of any kind.

## Building

Needs a JDK 11, since that is what the RuneLite client targets.

```
JAVA_HOME=/path/to/jdk-11 ./gradlew build
```

To run a development client with the plugin loaded:

```
JAVA_HOME=/path/to/jdk-11 ./gradlew run
```

`JAVA_HOME` is not pinned in `gradle.properties` on purpose - an absolute path
committed there works on exactly one machine.

## Notes on a few decisions

**Sell offers are valued at market price, not at what you listed them for.**
Listing something at a hopeful price does not make it worth that. Valuing
stock at the asking price inflated one test roster by 1.3 billion on a single
optimistic listing, and understates just as easily when something is listed
cheap. Buy offers are different and are valued at the listed price, because
the coins really are escrowed at that amount.

**Item quantities follow the game's own convention** - precise and yellow
below 100,000, truncated to K and white to 10 million, truncated to M and
green above. Truncated rather than rounded, so 9,999,999 reads as 9999K and
never crosses a threshold it has not reached.

**Bank contents are a snapshot.** A bank can only be read while it is open, so
each account's contents are from the last time you opened its bank in this
client. Grand Exchange offers have no such limit; the client reports all eight
slots shortly after login.

**Grand Exchange history starts when the plugin does.** The game exposes no
record of past trades, so nothing before the first run can be reconstructed.
Offers already in progress when the plugin first sees them are recorded
silently rather than dated to the moment they were noticed.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
