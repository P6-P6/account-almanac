package com.accountalmanac;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes everything the plugin knows into one self-contained HTML page.
 *
 * <p>One file, not a folder: it opens in a browser with no unpacking, and it
 * can be sent to somebody who has neither the plugin nor a spreadsheet. The
 * CSV exports remain the right answer for computing on the data; this is the
 * right answer for reading and sharing it.
 *
 * <p>Nothing is loaded from the network - no fonts, no scripts, no styles - so
 * the page renders identically offline and years from now. The sorting and
 * filtering are a few dozen lines of inline script rather than a library, for
 * the same reason.
 *
 * <p>Streamed a row at a time through a buffered writer, so a roster-wide
 * report of twelve thousand bank rows never exists as a string in memory.
 */
final class HtmlReport
{
	private HtmlReport()
	{
	}

	/**
	 * @return how many table rows were written in total
	 */
	static int write(File file, List<AccountRecord> accounts, HistoryStore historyStore,
		NamePrivacy privacy) throws IOException
	{
		int rows = 0;
		try (Writer w = new BufferedWriter(new OutputStreamWriter(
			Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8), 1 << 16))
		{
			head(w, accounts);
			rows += accountsSection(w, accounts, privacy);
			rows += itemsSection(w, accounts);
			rows += offersSection(w, accounts, privacy);
			rows += historySection(w, accounts, historyStore, privacy);
			rows += skillsSection(w, accounts, privacy);
			rows += banksSection(w, accounts, privacy);
			tail(w);
		}
		return rows;
	}

	// ---- sections ----------------------------------------------------------

	private static int accountsSection(Writer w, List<AccountRecord> accounts,
		NamePrivacy privacy) throws IOException
	{
		open(w, "accounts", "Accounts", accounts.size(),
			"Account", "Group", "Type", "Combat", "Total level", "Quests",
			"Bank", "GE", "Total wealth", "Last login");
		for (AccountRecord a : accounts)
		{
			w.write("<tr>");
			td(w, NameMasker.display(a, privacy));
			td(w, a.categoryLabel());
			td(w, AccountTypeBadge.fullLabel(a.accountType));
			num(w, a.combatLevel);
			num(w, a.totalLevel());
			tdRaw(w, a.hasQuestPoints() ? a.questPointsLabel() : "-", a.questPoints, true);
			gp(w, a.bankValue);
			gp(w, a.geValue());
			gp(w, a.totalWealth());
			tdRaw(w, a.lastLoginAt > 0 ? LoginAge.friendly(a.lastLoginAt) : "never",
				a.lastLoginAt, false);
			w.write("</tr>\n");
		}
		close(w);
		return accounts.size();
	}

	private static int itemsSection(Writer w, List<AccountRecord> accounts) throws IOException
	{
		List<ItemAggregator.ItemTotal> totals = ItemAggregator.aggregate(accounts);
		open(w, "items", "Items across all accounts", totals.size(),
			"Item", "Id", "Members", "Quantity", "Unit price", "Total value", "Accounts");
		for (ItemAggregator.ItemTotal t : totals)
		{
			w.write("<tr>");
			td(w, t.name);
			num(w, t.itemId);
			td(w, t.members == null ? "-" : (t.members ? "members" : "f2p"));
			num(w, t.totalQuantity);
			gp(w, t.unitPrice);
			gp(w, t.totalValue);
			num(w, t.accountCount());
			w.write("</tr>\n");
		}
		close(w);
		return totals.size();
	}

	private static int offersSection(Writer w, List<AccountRecord> accounts,
		NamePrivacy privacy) throws IOException
	{
		int n = 0;
		open(w, "offers", "Grand Exchange offers", countOffers(accounts),
			"Account", "Slot", "Type", "Item", "Progress", "Unit price",
			"vs market", "Committed");
		for (AccountRecord a : accounts)
		{
			String name = NameMasker.display(a, privacy);
			for (GrandExchangeRecord o : a.geOffers)
			{
				if (!o.isActive())
				{
					continue;
				}
				w.write("<tr>");
				td(w, name);
				num(w, o.slot + 1);
				td(w, o.typeLabel());
				td(w, o.itemName);
				bar(w, o.progress());
				gp(w, o.pricePerItem);
				Double gap = o.priceVsMarket();
				if (gap == null)
				{
					tdRaw(w, "-", Long.MIN_VALUE, true);
				}
				else
				{
					w.write("<td class=\"n ");
					w.write(o.priceGapFavourable() ? "good" : "bad");
					w.write("\" data-s=\"");
					w.write(Long.toString(Math.round(gap * 10000)));
					w.write("\">");
					esc(w, GeScreenshot.marketGapLabel(o));
					w.write("</td>");
				}
				gp(w, o.committedValue());
				w.write("</tr>\n");
				n++;
			}
		}
		close(w);
		return n;
	}

	private static int historySection(Writer w, List<AccountRecord> accounts,
		HistoryStore store, NamePrivacy privacy) throws IOException
	{
		int n = 0;
		open(w, "history", "Wealth and experience over time", -1,
			"Account", "When", "Bank", "GE", "Total wealth", "Total XP", "Total level");
		for (AccountRecord a : accounts)
		{
			AccountHistory h = store.historyFor(a.accountHash);
			if (h == null)
			{
				continue;
			}
			String name = NameMasker.display(a, privacy);
			for (HistorySnapshot s : h.snapshots)
			{
				w.write("<tr>");
				td(w, name);
				tdRaw(w, LoginAge.exact(s.at), s.at, false);
				gp(w, s.bankValue);
				gp(w, s.geValue);
				gp(w, s.totalWealth());
				num(w, s.totalXp);
				num(w, s.totalLevel);
				w.write("</tr>\n");
				n++;
			}
		}
		close(w);
		return n;
	}

	private static int skillsSection(Writer w, List<AccountRecord> accounts,
		NamePrivacy privacy) throws IOException
	{
		int n = 0;
		open(w, "skills", "Skills", -1, "Account", "Skill", "Level", "XP");
		for (AccountRecord a : accounts)
		{
			String name = NameMasker.display(a, privacy);
			for (Map.Entry<String, Integer> e : a.skillXp.entrySet())
			{
				Integer level = a.skillLevels.get(e.getKey());
				w.write("<tr>");
				td(w, name);
				td(w, e.getKey());
				num(w, level == null ? 0 : level);
				num(w, e.getValue() == null ? 0 : e.getValue());
				w.write("</tr>\n");
				n++;
			}
		}
		close(w);
		return n;
	}

	private static int banksSection(Writer w, List<AccountRecord> accounts,
		NamePrivacy privacy) throws IOException
	{
		int n = 0;
		open(w, "banks", "Every bank, one row per stack", -1,
			"Account", "Item", "Id", "Members", "Quantity", "Unit price", "Total value");
		for (AccountRecord a : accounts)
		{
			String name = NameMasker.display(a, privacy);
			for (BankItem i : a.bankItems)
			{
				if (i.quantity <= 0)
				{
					// Bank placeholder, not a held stack.
					continue;
				}
				w.write("<tr>");
				td(w, name);
				td(w, i.name);
				num(w, i.id);
				td(w, i.members == null ? "-" : (i.members ? "members" : "f2p"));
				num(w, i.quantity);
				gp(w, i.unitPrice);
				gp(w, i.totalValue());
				w.write("</tr>\n");
				n++;
			}
		}
		close(w);
		return n;
	}

	private static int countOffers(List<AccountRecord> accounts)
	{
		int n = 0;
		for (AccountRecord a : accounts)
		{
			for (GrandExchangeRecord o : a.geOffers)
			{
				if (o.isActive())
				{
					n++;
				}
			}
		}
		return n;
	}

	// ---- cells -------------------------------------------------------------

	private static void td(Writer w, String text) throws IOException
	{
		w.write("<td>");
		esc(w, text);
		w.write("</td>");
	}

	/** Number cell: right aligned, and sorted on its own value not its text. */
	private static void num(Writer w, long value) throws IOException
	{
		w.write("<td class=\"n\" data-s=\"");
		w.write(Long.toString(value));
		w.write("\">");
		esc(w, Format.exact(value));
		w.write("</td>");
	}

	private static void gp(Writer w, long value) throws IOException
	{
		w.write("<td class=\"n gp\" data-s=\"");
		w.write(Long.toString(value));
		w.write("\">");
		esc(w, Format.exact(value));
		w.write("</td>");
	}

	/** Text cell that sorts on a supplied key rather than the displayed text. */
	private static void tdRaw(Writer w, String text, long sortKey, boolean numeric)
		throws IOException
	{
		w.write(numeric ? "<td class=\"n\" data-s=\"" : "<td data-s=\"");
		w.write(Long.toString(sortKey));
		w.write("\">");
		esc(w, text);
		w.write("</td>");
	}

	/** Progress as a bar, coloured by the same bands the plugin uses. */
	private static void bar(Writer w, double fraction) throws IOException
	{
		int pct = (int) Math.round(fraction * 100);
		w.write("<td class=\"n\" data-s=\"");
		w.write(Integer.toString(pct));
		w.write("\"><span class=\"bar\"><i style=\"width:");
		w.write(Integer.toString(Math.max(0, Math.min(100, pct))));
		w.write("%;background:");
		w.write(hex(ProgressColours.forFraction(fraction)));
		w.write("\"></i></span> ");
		w.write(Integer.toString(pct));
		w.write("%</td>");
	}

	private static String hex(java.awt.Color c)
	{
		return String.format(Locale.ROOT, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	// ---- page ---------------------------------------------------------------

	private static void open(Writer w, String id, String title, int count, String... headers)
		throws IOException
	{
		w.write("<section id=\"");
		w.write(id);
		w.write("\"><h2>");
		esc(w, title);
		if (count >= 0)
		{
			w.write(" <span class=\"c\">");
			w.write(Format.exact(count));
			w.write("</span>");
		}
		w.write("</h2><input class=\"f\" placeholder=\"Filter these rows...\"><table><thead><tr>");
		for (String h : headers)
		{
			w.write("<th>");
			esc(w, h);
			w.write("</th>");
		}
		w.write("</tr></thead><tbody>\n");
	}

	private static void close(Writer w) throws IOException
	{
		w.write("</tbody></table></section>\n");
	}

	private static void head(Writer w, List<AccountRecord> accounts) throws IOException
	{
		long wealth = 0L;
		long xp = 0L;
		for (AccountRecord a : accounts)
		{
			wealth += a.totalWealth();
			xp += a.totalXp();
		}
		String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());

		w.write("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
			+ "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
			+ "<title>Account Almanac report</title><style>"
			+ ":root{--bg:#241f18;--panel:#2e2820;--line:#413a2e;--text:#ddd3bd;"
			+ "--dim:#9a8f79;--accent:#ff981f}"
			+ "*{box-sizing:border-box}"
			+ "body{margin:0;padding:24px 16px;background:var(--bg);color:var(--text);"
			+ "font:13px/1.5 system-ui,Segoe UI,Roboto,sans-serif}"
			+ ".wrap{max-width:1200px;margin:0 auto}"
			+ "h1{color:var(--accent);font-size:20px;margin:0 0 4px}"
			+ ".sub{color:var(--dim);margin:0 0 20px}"
			+ ".tot{display:flex;flex-wrap:wrap;gap:10px;margin:0 0 24px}"
			+ ".tot div{background:var(--panel);border:1px solid var(--line);"
			+ "border-radius:6px;padding:10px 14px;min-width:150px}"
			+ ".tot b{display:block;color:var(--accent);font-size:17px}"
			+ ".tot span{color:var(--dim)}"
			+ "nav{margin:0 0 20px}nav a{color:var(--accent);margin-right:14px}"
			+ "section{margin:0 0 30px}"
			+ "h2{font-size:15px;border-bottom:1px solid var(--line);padding-bottom:6px}"
			+ "h2 .c{color:var(--dim);font-weight:400;font-size:12px}"
			+ ".f{width:240px;max-width:100%;margin:0 0 8px;padding:5px 8px;"
			+ "background:var(--bg);border:1px solid var(--line);border-radius:4px;color:var(--text)}"
			+ ".tw{overflow-x:auto}"
			+ "table{border-collapse:collapse;width:100%;font-size:12px}"
			+ "th,td{padding:4px 8px;border-bottom:1px solid var(--line);text-align:left;"
			+ "white-space:nowrap}"
			+ "th{position:sticky;top:0;background:var(--panel);cursor:pointer;user-select:none}"
			+ "th:hover{color:var(--accent)}"
			+ "td.n{text-align:right;font-variant-numeric:tabular-nums}"
			+ "td.gp{color:#e0c46a}.good{color:#5ac85a}.bad{color:#d05040}"
			+ "tbody tr:nth-child(even){background:rgba(255,255,255,.02)}"
			+ ".bar{display:inline-block;width:60px;height:8px;background:#1d1913;"
			+ "border-radius:2px;overflow:hidden;vertical-align:middle;margin-right:6px}"
			+ ".bar i{display:block;height:100%}"
			+ "@media print{body{background:#fff;color:#000}th{background:#eee}}"
			+ "</style></head><body><div class=\"wrap\">");

		w.write("<h1>Account Almanac</h1><p class=\"sub\">");
		esc(w, accounts.size() + (accounts.size() == 1 ? " account" : " accounts")
			+ "  -  generated " + when);
		w.write("</p><div class=\"tot\"><div><b>");
		esc(w, Format.gp(wealth));
		w.write("</b><span>total wealth</span></div><div><b>");
		esc(w, Format.exact(xp));
		w.write("</b><span>total experience</span></div><div><b>");
		esc(w, Format.exact(countOffers(accounts)));
		w.write("</b><span>open offers</span></div></div>");

		w.write("<nav><a href=\"#accounts\">Accounts</a><a href=\"#items\">Items</a>"
			+ "<a href=\"#offers\">Offers</a><a href=\"#history\">History</a>"
			+ "<a href=\"#skills\">Skills</a><a href=\"#banks\">Banks</a></nav>");
	}

	private static void tail(Writer w) throws IOException
	{
		// Sorting reads data-s where a cell has one, so numbers and dates order
		// by value rather than by how they happen to be written.
		w.write("</div><script>\n"
			+ "document.querySelectorAll('table').forEach(function(t){\n"
			+ " t.parentNode.insertBefore(document.createElement('div'),t)"
			+ ".className='tw',t.previousSibling.appendChild(t);\n"
			+ " t.querySelectorAll('th').forEach(function(th,i){\n"
			+ "  th.addEventListener('click',function(){\n"
			+ "   var b=t.tBodies[0],r=[].slice.call(b.rows),"
			+ "asc=th.dataset.a!=='1';\n"
			+ "   t.querySelectorAll('th').forEach(function(o){o.dataset.a=''});\n"
			+ "   th.dataset.a=asc?'1':'';\n"
			+ "   r.sort(function(x,y){\n"
			+ "    var a=x.cells[i],c=y.cells[i];\n"
			+ "    if(a.dataset.s!==undefined&&c.dataset.s!==undefined)"
			+ "return (asc?1:-1)*(Number(a.dataset.s)-Number(c.dataset.s));\n"
			+ "    return (asc?1:-1)*a.textContent.localeCompare(c.textContent);\n"
			+ "   });\n"
			+ "   r.forEach(function(x){b.appendChild(x)});\n"
			+ "  });\n"
			+ " });\n"
			+ "});\n"
			+ "document.querySelectorAll('.f').forEach(function(inp){\n"
			+ " inp.addEventListener('input',function(){\n"
			+ "  var q=inp.value.toLowerCase(),"
			+ "b=inp.parentNode.querySelector('tbody');\n"
			+ "  [].forEach.call(b.rows,function(r){\n"
			+ "   r.style.display=!q||r.textContent.toLowerCase().indexOf(q)>-1?'':'none';\n"
			+ "  });\n"
			+ " });\n"
			+ "});\n"
			+ "</script></body></html>\n");
	}

	/** Minimal, correct HTML escaping. */
	private static void esc(Writer w, String text) throws IOException
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			switch (c)
			{
				case '&':
					w.write("&amp;");
					break;
				case '<':
					w.write("&lt;");
					break;
				case '>':
					w.write("&gt;");
					break;
				case '"':
					w.write("&quot;");
					break;
				default:
					w.write(c);
			}
		}
	}
}
