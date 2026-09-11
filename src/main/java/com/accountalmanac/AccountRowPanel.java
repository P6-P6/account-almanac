package com.accountalmanac;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One account's summary row in the sidebar: name, combat level, wealth
 * split between bank and GE, when its bank was last seen, and controls for
 * the user-editable login label and note.
 *
 * <p>Rebuilt wholesale by {@link AlmanacSidebarPanel} rather than mutated
 * in place, so it holds no listeners that would need unregistering.
 */
class AccountRowPanel extends JPanel
{
	private final AccountRecord record;
	private final AccountStore store;
	private final AccountAlmanacConfig config;
	private final Runnable onChanged;
	private final Runnable onRemove;

	/**
	 * @param onOpenStats double-click handler over the details area - opens
	 *                    the wealth viewer's Stats tab for this account
	 * @param onRemove    performs the actual deletion once both confirmations
	 *                    pass. Injected rather than done here because removing
	 *                    an account has to clear its snapshot history and GE
	 *                    events too, and those stores live a level up
	 */
	AccountRowPanel(AccountRecord record, AccountStore store,
		AccountAlmanacConfig config, Runnable onChanged, Runnable onOpenStats,
		Runnable onRemove)
	{
		this.record = record;
		this.store = store;
		this.config = config;
		this.onChanged = onChanged;
		this.onRemove = onRemove;

		setLayout(new BorderLayout());
		// A hidden account is dimmed rather than removed, so it is obvious at a
		// glance that it is present but excluded from the totals.
		setBackground(record.hidden
			? ColorScheme.DARK_GRAY_COLOR
			: ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, 250));
		setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel details = buildDetails(config);
		details.setToolTipText("Double-click to view stats");
		details.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// A plain MouseListener on the container alone only fires for gaps
		// between the labels - Swing delivers clicks to the deepest
		// component under the cursor, and a JLabel with no listener of its
		// own just swallows the event rather than passing it up. Attaching
		// the same listener to every child as well is what makes the whole
		// row double-clickable, not just the padding around the text.
		attachDoubleClick(details, onOpenStats);

		add(details, BorderLayout.CENTER);
		add(buildActions(), BorderLayout.SOUTH);
	}

	private static void attachDoubleClick(Component component, Runnable action)
	{
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					action.run();
				}
			}
		});
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				attachDoubleClick(child, action);
			}
		}
	}

	private JPanel buildDetails(AccountAlmanacConfig config)
	{
		JPanel details = new JPanel();
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.setOpaque(false);

		JLabel name = new JLabel(NameMasker.display(record, config.namePrivacy()));
		name.setForeground(ColorScheme.BRAND_ORANGE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));

		boolean hasIronBadge = AccountTypeBadge.isIronmanVariant(record.accountType);
		if (hasIronBadge || record.banned || record.hidden)
		{
			JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
			nameRow.setOpaque(false);
			nameRow.setAlignmentX(LEFT_ALIGNMENT);
			nameRow.add(name);
			if (hasIronBadge)
			{
				nameRow.add(buildTypeBadge());
			}
			if (record.banned)
			{
				nameRow.add(buildBadge("BANNED", config.staleLoginColour(),
					"Marked banned" + (record.bannedAt > 0L
						? " on " + LoginAge.exact(record.bannedAt)
						: "")));
			}
			if (record.hidden)
			{
				nameRow.add(buildBadge("HIDDEN", ColorScheme.MEDIUM_GRAY_COLOR,
					"Excluded from wealth totals and statistics"));
			}
			details.add(nameRow);
		}
		else
		{
			name.setAlignmentX(LEFT_ALIGNMENT);
			details.add(name);
		}

		long wealth = config.includeGrandExchange() ? record.totalWealth() : record.bankValue;
		JLabel value = new JLabel(Format.gp(wealth));
		value.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		value.setToolTipText(Format.exact(wealth) + " gp");
		value.setAlignmentX(LEFT_ALIGNMENT);
		details.add(value);

		long geValue = record.geValue();
		if (geValue > 0L)
		{
			details.add(small("GE: " + Format.gp(geValue)));
		}

		StringBuilder meta = new StringBuilder();
		if (record.combatLevel > 0)
		{
			meta.append("cb ").append(record.combatLevel);
		}
		if (record.totalLevel() > 0)
		{
			if (meta.length() > 0)
			{
				meta.append("  -  ");
			}
			meta.append(Format.exact(record.totalLevel())).append(" total");
		}
		if (record.hasQuestPoints())
		{
			if (meta.length() > 0)
			{
				meta.append("  -  ");
			}
			meta.append(record.questPointsLabel()).append(" qp");
		}
		if (!record.bankItems.isEmpty())
		{
			if (meta.length() > 0)
			{
				meta.append("  -  ");
			}
			meta.append(record.bankItems.size()).append(" items");
		}
		if (meta.length() > 0)
		{
			details.add(small(meta.toString()));
		}

		// Last login gets its own coloured line rather than being folded into
		// the meta row - it is the field most often being scanned for, and the
		// colour is the whole point of the green/yellow/red thresholds.
		long now = System.currentTimeMillis();
		JLabel lastLogin = small("last login: " + LoginAge.describeAge(record.lastLoginAt, now));
		lastLogin.setForeground(LoginAgeColours.forTimestamp(record.lastLoginAt, now, config));
		lastLogin.setToolTipText(record.lastLoginAt > 0L
			? LoginAge.friendly(record.lastLoginAt)
			: "This account has not been logged into since tracking began");
		details.add(lastLogin);

		String category = record.categoryLabel();
		if (!category.isEmpty())
		{
			details.add(small("group: " + category));
		}

		// "never opened" is meaningfully different from an empty bank: the
		// client simply has not seen this account's bank yet.
		details.add(small(record.hasBankSnapshot()
			? "bank seen " + Format.relativeTime(record.lastSnapshotAt)
			: "bank never opened"));

		int activeOffers = 0;
		for (GrandExchangeRecord offer : record.geOffers)
		{
			if (offer.isActive())
			{
				activeOffers++;
			}
		}
		if (activeOffers > 0)
		{
			details.add(small(activeOffers + (activeOffers == 1 ? " GE offer" : " GE offers")));
		}

		if (record.loginName != null && !record.loginName.isEmpty())
		{
			details.add(small("login name: "
				+ NameMasker.displayField(record.loginName, record.accountHash, config.namePrivacy())));
		}
		if (record.loginLabel != null && !record.loginLabel.isEmpty())
		{
			details.add(small("label: "
				+ NameMasker.displayField(record.loginLabel, record.accountHash, config.namePrivacy())));
		}
		if (record.note != null && !record.note.isEmpty())
		{
			details.add(small("note: " + record.note));
		}

		return details;
	}

	private JLabel small(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	/**
	 * Small filled pill (e.g. "HCIM") next to the account name. Painted
	 * directly rather than styled via a border, so the pill's rounded shape
	 * and fill are pixel-exact regardless of look-and-feel.
	 */
	/**
	 * The account type marker: the game's own helm icon where one exists,
	 * otherwise the text pill. Group ironman has no bundled art, and a failed
	 * image load returns null, so both fall through to text rather than
	 * leaving a gap where the marker should be.
	 */
	private JLabel buildTypeBadge()
	{
		BufferedImage icon = AccountTypeBadge.icon(record.accountType);
		if (icon != null)
		{
			JLabel badge = new JLabel(new ImageIcon(icon));
			badge.setToolTipText(AccountTypeBadge.fullLabel(record.accountType));
			return badge;
		}
		return buildBadge(
			AccountTypeBadge.shortLabel(record.accountType),
			AccountTypeBadge.color(record.accountType),
			AccountTypeBadge.fullLabel(record.accountType));
	}

	private JLabel buildBadge(String text, Color background, String tooltip)
	{
		JLabel badge = new JLabel(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				try
				{
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setColor(background);
					g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
				}
				finally
				{
					g2.dispose();
				}
				super.paintComponent(g);
			}
		};
		badge.setOpaque(false);
		badge.setForeground(Color.WHITE);
		badge.setFont(FontManager.getRunescapeSmallFont());
		badge.setHorizontalAlignment(SwingConstants.CENTER);
		badge.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
		badge.setToolTipText(tooltip);
		return badge;
	}

	private JPanel buildActions()
	{
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		actions.setOpaque(false);

		actions.add(button("Label", "Set a login label for this account", e -> editLoginLabel()));
		actions.add(button("Note", "Set a free-text note for this account", e -> editNote()));
		actions.add(button("Group", "Set this account's grouping, e.g. Main, Pure, Zerker",
			e -> editCategory()));
		actions.add(button(record.hidden ? "Unhide" : "Hide",
			record.hidden
				? "Count this account towards wealth totals and statistics again"
				: "Exclude this account from wealth totals and statistics, without deleting it",
			e -> toggleHidden()));
		actions.add(button(record.banned ? "Un-ban" : "Banned",
			record.banned
				? "Clear the banned flag on this account"
				: "Mark this account as banned",
			e -> toggleBanned()));
		actions.add(Box.createHorizontalStrut(4));
		actions.add(button("Remove", "Stop tracking this account", e -> confirmRemove()));

		return actions;
	}

	private JButton button(String text, String tooltip, java.awt.event.ActionListener listener)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setToolTipText(tooltip);
		button.setMargin(new java.awt.Insets(1, 5, 1, 5));
		button.addActionListener(listener);
		return button;
	}

	private void editLoginLabel()
	{
		String updated = (String) JOptionPane.showInputDialog(this,
			"Label identifying which login this account belongs to.\n"
				+ "This is a nickname only - never store a password here.",
			"Login label",
			JOptionPane.PLAIN_MESSAGE, null, null, record.loginLabel);

		if (updated != null)
		{
			store.updateLoginLabel(record.accountHash, updated.trim());
			onChanged.run();
		}
	}

	private void editNote()
	{
		String updated = (String) JOptionPane.showInputDialog(this,
			"Note for " + NameMasker.display(record, config.namePrivacy()),
			"Account note",
			JOptionPane.PLAIN_MESSAGE, null, null, record.note);

		if (updated != null)
		{
			store.updateNote(record.accountHash, updated.trim());
			onChanged.run();
		}
	}

	/**
	 * Editable combo rather than a fixed list, so the presets are one click
	 * away but a grouping the user invents still works.
	 */
	private void editCategory()
	{
		JComboBox<String> input = new JComboBox<>(
			AccountCategory.presetLabels().toArray(new String[0]));
		input.setEditable(true);
		input.setSelectedItem(record.category == null ? "" : record.category);

		int result = JOptionPane.showConfirmDialog(this, input,
			"Group for " + NameMasker.display(record, config.namePrivacy()),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION)
		{
			Object selected = input.getSelectedItem();
			store.updateCategory(record.accountHash,
				selected == null ? "" : selected.toString());
			onChanged.run();
		}
	}

	/**
	 * Hiding needs no confirmation - it destroys nothing and the button
	 * immediately offers to undo itself.
	 */
	private void toggleHidden()
	{
		store.updateHidden(record.accountHash, !record.hidden);
		onChanged.run();
	}

	private void toggleBanned()
	{
		store.updateBanned(record.accountHash, !record.banned);
		onChanged.run();
	}

	/**
	 * Two separate confirmations, not one dialog worded strongly - a second
	 * dialog is a second deliberate click, which a single "are you sure"
	 * does not force. Removal deletes the account's entire stored history
	 * (bank snapshot, GE offers, skills) with no undo.
	 */
	private void confirmRemove()
	{
		int first = JOptionPane.showConfirmDialog(this,
			"Stop tracking " + NameMasker.display(record, config.namePrivacy()) + "?\n\n"
				+ "This deletes its stored bank snapshot, GE offers, skill history, "
				+ "wealth snapshots and Grand Exchange event log. There is no undo.",
			"Remove account",
			JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

		if (first != JOptionPane.YES_OPTION)
		{
			return;
		}

		int second = JOptionPane.showConfirmDialog(this,
			"Really remove " + NameMasker.display(record, config.namePrivacy())
				+ "? This cannot be undone.",
			"Confirm removal",
			JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

		if (second == JOptionPane.YES_OPTION)
		{
			onRemove.run();
		}
	}
}
