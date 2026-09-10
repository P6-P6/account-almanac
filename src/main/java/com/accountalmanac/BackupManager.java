package com.accountalmanac;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Timestamped local copies of the tracked data files.
 *
 * <p>Backups live in {@code .runelite/accountalmanac/backups/}, inside the
 * plugin's own directory - the guidelines allow writing only under
 * {@code .runelite}, and putting them anywhere else would also mean the user
 * had to know a second location to look in.
 *
 * <p>The time of the last backup is read from the folder names rather than
 * stored in config. One less piece of state to keep in sync, and it stays
 * correct if the user deletes a backup folder by hand.
 */
@Slf4j
class BackupManager
{
	private static final String BACKUPS_DIR = "backups";
	private static final String PREFIX = "backup-";

	/** Sorts lexicographically in time order, which is why it is used for folder names. */
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final File pluginDir;

	BackupManager()
	{
		this.pluginDir = new File(RuneLite.RUNELITE_DIR, "accountalmanac");
	}

	File backupsDir()
	{
		return new File(pluginDir, BACKUPS_DIR);
	}

	/**
	 * Runs a backup if one is due.
	 *
	 * @return the folder written, or {@code null} if nothing was due or the
	 *         copy failed
	 */
	File backupIfDue(long now, int intervalDays)
	{
		long last = lastBackupAt();
		if (last > 0L && now - last < TimeUnit.DAYS.toMillis(Math.max(1, intervalDays)))
		{
			return null;
		}
		return backupNow(now);
	}

	/**
	 * Copies every data file that exists into a new timestamped folder.
	 *
	 * <p>Returns {@code null} rather than an empty folder when there is nothing
	 * to copy, so a fresh install does not accumulate empty backups.
	 */
	File backupNow(long now)
	{
		File target = new File(backupsDir(), PREFIX + STAMP.format(local(now)));
		if (target.exists())
		{
			// Same-second retry - the existing folder is already this backup.
			return target;
		}
		if (!target.mkdirs())
		{
			log.warn("Failed to create backup directory {}", target);
			return null;
		}

		int copied = 0;
		for (String name : new String[]{"accounts.json", "history.json", "ge-events.json"})
		{
			File source = new File(pluginDir, name);
			if (!source.exists())
			{
				continue;
			}
			try
			{
				Files.copy(source.toPath(), new File(target, name).toPath(),
					StandardCopyOption.REPLACE_EXISTING);
				copied++;
			}
			catch (IOException e)
			{
				log.warn("Failed to back up {}", name, e);
			}
		}

		if (copied == 0)
		{
			// Nothing was there to save; do not leave an empty folder behind.
			deleteRecursively(target);
			return null;
		}

		log.debug("Wrote backup {} ({} files)", target.getName(), copied);
		return target;
	}

	/** Deletes the oldest backups until only {@code keep} remain. */
	int pruneOldBackups(int keep)
	{
		List<File> backups = listBackups();
		int limit = Math.max(1, keep);
		if (backups.size() <= limit)
		{
			return 0;
		}

		int removed = 0;
		// listBackups() is newest first, so everything past the limit is older.
		for (File stale : backups.subList(limit, backups.size()))
		{
			if (deleteRecursively(stale))
			{
				removed++;
			}
		}
		return removed;
	}

	/** Existing backup folders, newest first. */
	List<File> listBackups()
	{
		File dir = backupsDir();
		File[] entries = dir.listFiles(f -> f.isDirectory() && f.getName().startsWith(PREFIX));
		if (entries == null || entries.length == 0)
		{
			return Collections.emptyList();
		}
		List<File> backups = new ArrayList<>(Arrays.asList(entries));
		backups.sort(Comparator.comparing(File::getName).reversed());
		return backups;
	}

	/** Epoch millis of the most recent backup, or 0 if there is none. */
	long lastBackupAt()
	{
		List<File> backups = listBackups();
		for (File backup : backups)
		{
			long at = parseStamp(backup.getName());
			if (at > 0L)
			{
				return at;
			}
		}
		return 0L;
	}

	/**
	 * Parses the timestamp back out of a folder name, returning 0 for anything
	 * that does not parse - a folder the user renamed should be listed but must
	 * not be mistaken for a recent backup.
	 */
	private static long parseStamp(String folderName)
	{
		if (!folderName.startsWith(PREFIX))
		{
			return 0L;
		}
		try
		{
			LocalDateTime parsed = LocalDateTime.parse(folderName.substring(PREFIX.length()), STAMP);
			return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		}
		catch (DateTimeParseException e)
		{
			return 0L;
		}
	}

	private static boolean deleteRecursively(File file)
	{
		File[] children = file.listFiles();
		if (children != null)
		{
			for (File child : children)
			{
				deleteRecursively(child);
			}
		}
		return file.delete();
	}

	private static LocalDateTime local(long epochMillis)
	{
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
	}
}
