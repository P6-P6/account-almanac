package com.accountalmanac;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Streams rows to a CSV file.
 *
 * <p>Written a row at a time straight through a buffered writer. Building the
 * whole file in memory first would mean holding a second copy of a bank
 * export - twelve thousand rows across forty accounts - for no gain, since
 * nothing needs to see the text before it lands on disk.
 *
 * <p>Quoting follows RFC 4180: a field is quoted only when it has to be, and
 * embedded quotes are doubled. Values are written raw - unformatted numbers,
 * epoch millis for times - because the point of a CSV is to be computed on. A
 * spreadsheet can format 1200000 however the reader likes; it cannot get the
 * number back out of "1.2M".
 */
final class Csv implements Closeable
{
	private final Writer out;
	/** Reused across rows; a per-row builder would allocate one per line. */
	private final StringBuilder line = new StringBuilder(256);

	private Csv(Writer out)
	{
		this.out = out;
	}

	/**
	 * Opens a file and writes the header row.
	 *
	 * <p>UTF-8 without a byte order mark. A BOM would make Excel guess the
	 * encoding correctly on a double-click, but it also shows up as stray
	 * characters in every parser that does not expect one, and these files are
	 * meant to be read by tools as much as by people.
	 */
	static Csv open(File file, String... headers) throws IOException
	{
		Writer w = new BufferedWriter(new OutputStreamWriter(
			Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8), 1 << 16);
		Csv csv = new Csv(w);
		csv.row((Object[]) headers);
		return csv;
	}

	/** Writes one row. Nulls become empty fields. */
	void row(Object... cells) throws IOException
	{
		line.setLength(0);
		for (int i = 0; i < cells.length; i++)
		{
			if (i > 0)
			{
				line.append(',');
			}
			append(cells[i]);
		}
		// \r\n is what RFC 4180 specifies, and it is what Excel expects on
		// Windows regardless of what the file is opened with elsewhere.
		line.append('\r').append('\n');
		out.write(line.toString());
	}

	private void append(Object cell)
	{
		if (cell == null)
		{
			return;
		}
		// Numbers and booleans can never need quoting or escaping, and they are
		// the bulk of every export, so they skip the scan entirely.
		if (cell instanceof Number || cell instanceof Boolean)
		{
			line.append(cell);
			return;
		}

		String text = cell.toString();
		if (text.isEmpty())
		{
			return;
		}

		boolean quote = needsQuoting(text);
		if (!quote)
		{
			line.append(text);
			return;
		}

		line.append('"');
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == '"')
			{
				line.append('"');
			}
			line.append(c);
		}
		line.append('"');
	}

	/**
	 * Whether a field has to be quoted: it contains the separator, a quote, or
	 * a line break.
	 *
	 * <p>Deliberately nothing more. Quoting a field that begins with {@code =}
	 * is sometimes offered as protection against a spreadsheet evaluating it
	 * as a formula, but it is not: Excel drops the quotes on import and
	 * evaluates it anyway. The only things that work are mangling the value or
	 * refusing to write it, and neither belongs in an export of the user's own
	 * data that the same user is about to open.
	 */
	private static boolean needsQuoting(String text)
	{
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == ',' || c == '"' || c == '\n' || c == '\r')
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() throws IOException
	{
		out.close();
	}
}
