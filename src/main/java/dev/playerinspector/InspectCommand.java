package dev.playerinspector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class InspectCommand implements CommandExecutor, TabCompleter {
    private final PlayerInspectorPlugin plugin;

    public InspectCommand(PlayerInspectorPlugin plugin) { this.plugin = plugin; }

    public static boolean isAuthorized(CommandSender sender) {
        return sender instanceof ConsoleCommandSender
                || (sender instanceof Player player && player.isOp()
                        && player.hasPermission("playerinspector.use"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isAuthorized(sender)) {
            sender.sendMessage(ChatColor.RED + "Only server operators can use PlayerInspector.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "Usage: /" + label + " <player> "
                    + "[inventory|ender|shulkers|bundles|death|stats|edit|editender|backup|backups|restore|confirm]");
            sender.sendMessage(ChatColor.YELLOW + "Restore: /" + label
                    + " <player> restore <death|manual|safety|backup-ID> [inventory|ender|all], then confirm");
            return true;
        }
        OfflinePlayer target = findPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found in server history: " + args[0]);
            return true;
        }
        String section = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "overview";
        switch (section) {
            case "overview", "gui" -> {
                if (sender instanceof Player admin) plugin.menus().openOverview(admin, target);
                else sendStats(sender, target);
            }
            case "inv", "inventory" -> openRead(sender, target, true);
            case "ec", "ender", "enderchest" -> openRead(sender, target, false);
            case "shulker", "shulkers", "shulkerbox", "shulkerboxes" -> openContainers(sender, target, true, args);
            case "bundle", "bundles" -> openContainers(sender, target, false, args);
            case "death", "deaths", "lastdeath" -> {
                if (sender instanceof Player admin) plugin.menus().openDeath(admin, target);
                else sendStats(sender, target);
            }
            case "stats", "statistics" -> sendStats(sender, target);
            case "edit", "editinv", "editinventory" -> openEdit(sender, target, false);
            case "editender", "editenderchest" -> openEdit(sender, target, true);
            case "backup" -> {
                Player live = onlineOrExplain(sender, target);
                if (live != null) plugin.createManualBackup(sender, live);
            }
            case "backups" -> listBackups(sender, target, args.length > 2 ? args[2] : "1");
            case "restore" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /pi <player> restore <death|manual|safety|backup-ID> [inventory|ender|all]");
                } else {
                    Player live = onlineOrExplain(sender, target);
                    if (live != null) plugin.restorer().request(sender, live,
                            args[2].toLowerCase(Locale.ROOT),
                            args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "inventory");
                }
            }
            case "confirm" -> {
                Player live = onlineOrExplain(sender, target);
                if (live != null) plugin.restorer().confirm(sender, live);
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown section. Try /pi for help.");
        }
        return true;
    }

    private void listBackups(CommandSender sender, OfflinePlayer target, String pageText) {
        int page;
        try { page = Integer.parseInt(pageText); }
        catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Usage: /pi <player> backups [page number]");
            return;
        }
        if (page < 1) {
            sender.sendMessage(ChatColor.RED + "Backup page must be at least 1.");
            return;
        }
        List<BackupRevision> revisions = plugin.store().listBackups(target.getUniqueId());
        int pages = Math.max(1, (revisions.size() + 11) / 12);
        if (page > pages) {
            sender.sendMessage(ChatColor.RED + "Only " + pages + " backup pages exist.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Backup history for " + targetName(target)
                + " (newest first, page " + page + "/" + pages + "):");
        if (revisions.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No backups yet; run /pi <player> backup while online.");
            return;
        }
        List<BackupRevision> unresolved = plugin.store().unresolvedRestores(target.getUniqueId());
        for (int i = (page - 1) * 12; i < Math.min(page * 12, revisions.size()); i++) {
            BackupRevision revision = revisions.get(i);
            boolean isUnresolved = unresolved.contains(revision);
            sender.sendMessage(ChatColor.GRAY + "- " + revision.kind() + " "
                    + ChatColor.AQUA + revision.shortId() + ChatColor.GRAY + " | "
                    + TextUtil.date(revision.createdAt())
                    + (isUnresolved ? ChatColor.RED + " | UNRESOLVED restore tx "
                            + revision.transactionId().substring(0, 12) : ""));
        }
        if (page < pages) sender.sendMessage(ChatColor.YELLOW + "More: /pi " + targetName(target)
                + " backups " + (page + 1));
        sender.sendMessage(ChatColor.YELLOW + "Restore: /pi " + targetName(target)
                + " restore <backup-ID> [inventory|ender|all]");
    }

    private static Player onlineOrExplain(CommandSender sender, OfflinePlayer target) {
        Player live = target.getPlayer();
        if (live == null || !live.isOnline()) {
            sender.sendMessage(ChatColor.YELLOW + "Target must be ONLINE for editing/restoring. "
                    + "Offline inventory snapshots remain inspectable.");
            return null;
        }
        return live;
    }

    private void openRead(CommandSender sender, OfflinePlayer target, boolean inventory) {
        if (sender instanceof Player admin) {
            if (inventory) plugin.menus().openInventory(admin, target);
            else plugin.menus().openEnderChest(admin, target);
        } else sender.sendMessage(ChatColor.YELLOW + "A GUI requires an in-game operator.");
    }

    private void openContainers(CommandSender sender, OfflinePlayer target, boolean shulker, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(ChatColor.YELLOW + "Container browsing requires an in-game operator.");
            return;
        }
        String source = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "all";
        if (!List.of("all", "inventory", "ender", "death").contains(source) || args.length > 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /pi <player> "
                    + (shulker ? "shulkers" : "bundles") + " [all|inventory|ender|death]");
            return;
        }
        plugin.menus().openContainers(admin, target, shulker, source);
    }

    private void openEdit(CommandSender sender, OfflinePlayer target, boolean ender) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(ChatColor.YELLOW + "A live editor requires an in-game operator.");
            return;
        }
        if (!RemoteInventoryEditor.canEdit(admin)) {
            admin.sendMessage(ChatColor.RED + "Editing requires OP and playerinspector.edit.");
            return;
        }
        Player live = onlineOrExplain(sender, target);
        if (live != null) plugin.editor().open(admin, live, ender);
    }

    public static String targetName(OfflinePlayer target) {
        return target.getName() != null ? target.getName() : target.getUniqueId().toString();
    }

    public void sendStats(CommandSender sender, OfflinePlayer target) {
        sender.sendMessage(ChatColor.GOLD + "[PlayerInspector] " + ChatColor.AQUA + targetName(target));
        sender.sendMessage(ChatColor.GRAY + "Total playtime: " + ChatColor.WHITE
                + TextUtil.playTime(Integer.toUnsignedLong(target.getStatistic(Statistic.PLAY_ONE_MINUTE))));
        sender.sendMessage(ChatColor.GRAY + "Total deaths: " + ChatColor.WHITE + target.getStatistic(Statistic.DEATHS));
        sender.sendMessage(ChatColor.GRAY + "Player kills: " + ChatColor.WHITE + target.getStatistic(Statistic.PLAYER_KILLS));
        sender.sendMessage(ChatColor.GRAY + "Mob kills: " + ChatColor.WHITE + target.getStatistic(Statistic.MOB_KILLS));
        LastDeath last = plugin.store().getLastDeath(target.getUniqueId());
        if (last == null) {
            sender.sendMessage(ChatColor.YELLOW + "No detailed death recorded by PlayerInspector yet.");
            Location vanilla = target.getLastDeathLocation();
            if (vanilla != null) sender.sendMessage(ChatColor.GRAY + "Vanilla last death coordinates: "
                    + ChatColor.WHITE + location(vanilla) + " (time/cause/inventory unavailable)");
            return;
        }
        DeathRecord record = last.details();
        sender.sendMessage(ChatColor.GRAY + "Last death: " + ChatColor.WHITE + TextUtil.date(record.time()));
        sender.sendMessage(ChatColor.GRAY + "Cause: " + ChatColor.WHITE + TextUtil.readable(record.cause())
                + ChatColor.GRAY + " | Attacker: " + ChatColor.WHITE + record.attacker());
        sender.sendMessage(ChatColor.GRAY + "Direct entity: " + ChatColor.WHITE + record.directEntity());
        sender.sendMessage(ChatColor.GRAY + "Location: " + ChatColor.WHITE + record.location());
        sender.sendMessage(ChatColor.GRAY + "Inventory at death: " + ChatColor.WHITE
                + (last.inventory() == null ? "Unavailable (older record)" : "Saved (view with /pi "
                    + targetName(target) + " death)"));
        if (!record.message().isBlank()) sender.sendMessage(ChatColor.GRAY + "Death message: "
                + ChatColor.WHITE + TextUtil.singleLine(record.message(), 160));
    }

    private static String location(Location loc) {
        return (loc.getWorld() != null ? loc.getWorld().getName() : "unknown")
                + " [" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "]";
    }

    private static OfflinePlayer findPlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player != null) return player;
        try {
            UUID uuid = UUID.fromString(name);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            return offline.hasPlayedBefore() || offline.isOnline() ? offline : null;
        } catch (IllegalArgumentException ignored) {
            // Name, not UUID.
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) return offline;
        }
        return null;
    }

    private static boolean isContainerCommand(String name) {
        return List.of("shulker", "shulkers", "shulkerbox", "shulkerboxes", "bundle", "bundles")
                .contains(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isAuthorized(sender)) return List.of();
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    names.add(player.getName());
            }
            return names;
        }
        List<String> options = switch (args.length) {
            case 2 -> Arrays.asList("overview", "inventory", "ender", "shulkers", "bundles", "stats", "death",
                    "edit", "editender", "backup", "backups", "restore", "confirm");
            case 3 -> args[1].equalsIgnoreCase("restore") ? Arrays.asList("death", "manual", "safety")
                    : isContainerCommand(args[1]) ? Arrays.asList("all", "inventory", "ender", "death") : List.of();
            case 4 -> args[1].equalsIgnoreCase("restore") ? Arrays.asList("inventory", "ender", "all") : List.of();
            default -> List.of();
        };
        return options.stream().filter(s -> s.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
    }
}
