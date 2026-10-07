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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Atomic read/write of one JSON file, shared by every store.
 *
 * <p>This is the same write dance {@link AccountStore} performs: serialise to a
 * sibling temporary file then {@link Files#move} it over the target with
 * {@code REPLACE_EXISTING}. {@code File.renameTo} is not used because it
 * silently refuses to overwrite an existing destination on Windows, unlike
 * POSIX {@code rename}. Writing in place instead would leave a truncated file
 * if the process died mid-write, which for this plugin means losing tracked
 * history that cannot be reconstructed.
 *
 * <p>Several clients share one data folder, so writing is also the point
 * where they would overwrite each other. {@link #locked} serialises a store's
 * whole read-merge-write against the other clients, and each write goes
 * through a temporary file no other client names.
 */
@Slf4j
final class JsonFile
{
	/**
	 * Distinguishes this client's temporary files from another client's.
	 *
	 * <p>Several clients run against one data folder, so a temporary name fixed
	 * as {@code accounts.json.tmp} is the same path in every one of them. Two
	 * clients saving at once then write into a single file and whichever
	 * finishes first moves the interleaving of both into place - a corrupt
	 * roster, not merely a lost save. A name no other client uses keeps each
	 * write to itself; the {@code move} that follows is atomic, so the file the
	 * others read is always one client's complete copy.
	 */
	private static final String WRITER_ID = UUID.randomUUID().toString().substring(0, 8);

	/** Separates concurrent writes to one file inside this client too. */
	private static final AtomicLong WRITE_COUNT = new AtomicLong();

	/**
	 * One monitor per data file, because a file lock is held per process.
	 *
	 * <p>Two threads of this client asking the operating system to lock the
	 * same file is an {@link java.nio.channels.OverlappingFileLockException},
	 * not a wait, so threads queue here before the lock is taken.
	 */
	private static final ConcurrentMap<String, Object> MONITORS = new ConcurrentHashMap<>();

	private JsonFile()
	{
	}

	private static File tempFor(File file)
	{
		return new File(file.getParentFile(),
			file.getName() + "." + WRITER_ID + "." + WRITE_COUNT.incrementAndGet() + ".tmp");
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
	/**
	 * Writes already-serialised JSON.
	 *
	 * <p>Split from serialisation on purpose. Callers hold a lock while turning
	 * their data into text - it has to be consistent - but must not hold it
	 * across the disk write, because the client thread contends on that same
	 * lock and would then be blocked on IO.
	 */
	static boolean writeText(File file, String json)
	{
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Failed to create directory {}", dir);
			return false;
		}

		File tmp = tempFor(file);
		try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))
		{
			writer.write(json);
		}
		catch (IOException e)
		{
			log.warn("Failed to write {}", tmp.getName(), e);
			tmp.delete();
			return false;
		}

		return replace(tmp, file);
	}

	static boolean write(File file, Gson gson, Object data)
	{
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Failed to create directory {}", dir);
			return false;
		}

		File tmp = tempFor(file);
		try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))
		{
			gson.toJson(data, writer);
		}
		catch (IOException e)
		{
			log.warn("Failed to write {}", tmp.getName(), e);
			tmp.delete();
			return false;
		}

		return replace(tmp, file);
	}

	/**
	 * Runs {@code action} with every other client locked out of {@code file}.
	 *
	 * <p>Saving is read, merge, write: the file is read so another client's
	 * work can be carried over, because the write that follows replaces the
	 * whole file. Between that read and that write a second client can write
	 * its own copy, which this client then overwrites - and if that second
	 * client is closing, it has no later save left to put its data back.
	 * Holding a lock across all three closes the gap.
	 *
	 * <p>The lock is taken on a separate {@code .lock} file: locking the data
	 * file itself would mean holding a handle to the file being replaced, which
	 * is what Windows refuses. It also serialises the replacing move, which
	 * fails outright when two clients attempt it at once.
	 *
	 * <p>A lock that cannot be taken at all - an exotic or read-only
	 * filesystem - is not treated as a reason to skip the save: the action runs
	 * unlocked, which is how this worked before, rather than not at all. The
	 * operating system drops the lock if the client dies holding it.
	 */
	static <T> T locked(File file, Supplier<T> action)
	{
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Failed to create directory {}", dir);
			return action.get();
		}

		File lockFile = new File(dir, file.getName() + ".lock");
		Object monitor = MONITORS.computeIfAbsent(lockFile.getPath(), path -> new Object());
		synchronized (monitor)
		{
			// Taken in its own block: a failure here is reported and carried
			// on from, but a failure inside the action is the action's own.
			FileChannel channel = null;
			FileLock lock = null;
			try
			{
				channel = FileChannel.open(lockFile.toPath(),
					StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				lock = channel.lock();
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Could not lock {}; saving without it", lockFile.getName(), e);
			}

			try
			{
				return action.get();
			}
			finally
			{
				release(lock, channel, lockFile);
			}
		}
	}

	private static void release(FileLock lock, FileChannel channel, File lockFile)
	{
		try
		{
			// Nested, so a failure to release still closes the channel - which
			// releases the lock anyway. Leaking it would lock out every later
			// save, this client's included.
			try
			{
				if (lock != null)
				{
					lock.release();
				}
			}
			finally
			{
				if (channel != null)
				{
					channel.close();
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to release {}", lockFile.getName(), e);
		}
	}

	/**
	 * Moves a finished temporary file over the real one.
	 *
	 * <p>The temporary file is removed when the move fails, so a folder shared
	 * by several clients does not collect one leftover per failed save.
	 */
	private static boolean replace(File tmp, File file)
	{
		try
		{
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return true;
		}
		catch (IOException e)
		{
			log.warn("Failed to replace {}", file.getName(), e);
			tmp.delete();
			return false;
		}
	}
}
