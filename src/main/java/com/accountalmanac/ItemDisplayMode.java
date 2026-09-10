package com.accountalmanac;

/**
 * How an item is shown in the Grand Exchange log and trade history.
 *
 * <p>Public because it is a return type on the public
 * {@link AccountAlmanacConfig} interface, which RuneLite implements with a
 * dynamic proxy that cannot reach package-private types.
 */
public enum ItemDisplayMode
{
	ICON_AND_NAME("Icon and name"),
	NAME_ONLY("Name only"),
	ICON_ONLY("Icon only");

	private final String label;

	ItemDisplayMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	boolean showsIcon()
	{
		return this != NAME_ONLY;
	}

	boolean showsName()
	{
		return this != ICON_ONLY;
	}
}
