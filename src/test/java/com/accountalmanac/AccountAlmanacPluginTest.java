package com.accountalmanac;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AccountAlmanacPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AccountAlmanacPlugin.class);
		RuneLite.main(args);
	}
}
