package com.accountalmanac;

/**
 * How account names are shown in the UI.
 *
 * <p>Display only. Nothing here is ever written back to disk - the stored
 * roster, the Grand Exchange event log and every backup keep the real names,
 * so switching back to {@link #REAL} restores them immediately and no data is
 * lost by turning masking on.
 *
 * <p>Public because it is a return type on the public
 * {@link AccountAlmanacConfig} interface. RuneLite implements that interface
 * with a JDK dynamic proxy, which lives in a different runtime package - a
 * package-private return type is unreachable from it and throws
 * {@code IllegalAccessError} as the plugin starts, taking the whole plugin
 * down with it.
 */
public enum NamePrivacy
{
	REAL("Show real names"),

	/**
	 * A stable stand-in name per account. Stable matters: a name that changed
	 * between renders would make charts and tables impossible to follow, which
	 * would defeat the purpose rather than just hiding.
	 */
	RANDOM("Randomised stand-in names"),

	/** No name at all, just a short id derived from the account hash. */
	HIDDEN("Hidden");

	private final String label;

	NamePrivacy(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	boolean isMasked()
	{
		return this != REAL;
	}
}
