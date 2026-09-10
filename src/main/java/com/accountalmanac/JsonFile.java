package com.accountalmanac;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Atomic read/write of one JSON file, shared by the history and GE event
 * stores.
 *
 * <p>This is the same write dance {@link AccountStore} performs: serialise to a
 * sibling {@code .tmp} then {@link Files#move} it over the target with
 * {@code REPLACE_EXISTING}. {@code File.renameTo} is not used because it
 * silently refuses to overwrite an existing destination on Windows, unlike
 * POSIX {@code rename}. Writing in place instead would leave a truncated file
 * if the process died mid-write, which for this plugin means losing tracked
 * history that cannot be reconstructed.
 *
 * <p>{@code AccountStore} keeps its own copy of this logic rather than being
 * retrofitted onto it. Its version is in service and proven against the user's
 * live 40-account file, and rewriting working persistence code as a side
 * effect of adding features is how data gets lost.
 */
@Slf4j
final class JsonFile
{
	private JsonFile()
	{
	}

	/**
	 * Reads and normalises a JSON file, falling back to {@code empty} for a
	 * missing, unreadable or malformed file.
	 *
	 * <p>A parse failure is warned about and swallowed rather than propagated:
	 * a corrupt history file should cost the user their history, not stop the
	 * plugin from tracking accounts.
	 */
	static <T> T read(File file, Gson gson, Class<T> type, Supplier<T> empty)
	{
		if (file == null || !file.exists())
		{
			return empty.get();
		}
		try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
		{
			T loaded = gson.fromJson(reader, type);
			return loaded == null ? empty.get() : loaded;
		}
		catch (JsonParseException | IOException e)
		{
			log.warn("Failed to read {}; starting empty", file.getName(), e);
			return empty.get();
		}
	}

	/**
	 * @return {@code true} if the file was fully written and swapped in
	 */
	static boolean write(File file, Gson gson, Object data)
	{
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Failed to create directory {}", dir);
			return false;
		}

		File tmp = new File(dir, file.getName() + ".tmp");
		try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))
		{
			gson.toJson(data, writer);
		}
		catch (IOException e)
		{
			log.warn("Failed to write {}", tmp.getName(), e);
			return false;
		}

		try
		{
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return true;
		}
		catch (IOException e)
		{
			log.warn("Failed to replace {}", file.getName(), e);
			return false;
		}
	}
}
