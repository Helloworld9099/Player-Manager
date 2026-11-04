package dev.playerinspector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A session-scoped, Bukkit-only staff vanish. This never hides server-side presence
 * from console commands, other plugins, proxies, server pings, or web maps.
 *
 * All Bukkit visibility changes happen on the main server thread. The set is safe
 * to query in the legacy asynchronous chat event.
 */
public final class VanishManager implements Listener, CommandExecutor, TabCompleter {
    private final PlayerInspectorPlugin plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public VanishManager(PlayerInspectorPlugin plugin) {
        this.plugin = plugin;
    }

    static boolean staffCanSee(boolean isOp, boolean hasSeePermission) {
        return isOp || hasSeePermission;
    }

    static String formatMessage(String template, String playerName) {
        String clean = template == null ? "" : template.replace('\n', ' ').replace('\r', ' ');
        clean = clean.replace("{player}", playerName);
        if (clean.length() > 180) clean = clean.substring(0, 180);
        return ChatColor.translateAlternateColorCodes('&', clean);
    }

    private boolean isStaffViewer(Player viewer) {
        return staffCanSee(viewer.isOp(), viewer.hasPermission("playerinspector.vanish.see"));
    }

    public boolean isVanished(UUID id) {
        return vanished.contains(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only in-game operators can toggle vanish.");
            return true;
        }
        if (!player.isOp() || !player.hasPermission("playerinspector.vanish")) {
            player.sendMessage(ChatColor.RED + "Vanish requires OP and playerinspector.vanish.");
            return true;
        }
        if (args.length > 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " [on|off|status]");
            return true;
        }
        String mode = args.length == 0 ? "toggle" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("status")) {
            player.sendMessage(ChatColor.AQUA + "Vanish: " + (isVanished(player.getUniqueId()) ? "ON" : "OFF"));
            return true;
        }
        if (!mode.equals("toggle") && !mode.equals("on") && !mode.equals("off")) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " [on|off|status]");
            return true;
        }
        boolean enable = mode.equals("on") || (mode.equals("toggle") && !isVanished(player.getUniqueId()));
        if (enable == isVanished(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "You are already " + (enable ? "vanished." : "visible."));
            return true;
        }
        if (enable) vanish(player);
        else unvanish(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()
                || !player.hasPermission("playerinspector.vanish") || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("on", "off", "status").stream().filter(option -> option.startsWith(prefix)).toList();
    }

    private void vanish(Player target) {
        vanished.add(target.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) continue;
            if (isStaffViewer(viewer)) continue;
            viewer.hidePlayer(plugin, target);
            sendFake(viewer, target, "leave-message", "&e{player} left the game");
        }
        target.sendMessage(ChatColor.GREEN + "You are now vanished. Regular players cannot see you.");
        plugin.getLogger().info(target.getName() + " enabled vanish.");
    }

    private void unvanish(Player target) {
        vanished.remove(target.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) continue;
            boolean hiddenFromViewer = !isStaffViewer(viewer);
            viewer.showPlayer(plugin, target);
            if (hiddenFromViewer) sendFake(viewer, target, "join-message", "&e{player} joined the game");
        }
        target.sendMessage(ChatColor.GREEN + "Vanish disabled. You are visible unless another plugin hides you.");
        plugin.getLogger().info(target.getName() + " disabled vanish.");
    }

    private void sendFake(Player viewer, Player target, String key, String fallback) {
        if (!plugin.getConfig().getBoolean("vanish.fake-messages", true)) return;
        String message = formatMessage(plugin.getConfig().getString("vanish." + key, fallback), target.getName());
        if (!message.isBlank()) viewer.sendMessage(message);
    }

    private void applyForViewer(Player viewer) {
        if (!viewer.isOnline()) return;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!vanished.contains(target.getUniqueId()) || viewer.getUniqueId().equals(target.getUniqueId())) continue;
            if (isStaffViewer(viewer)) viewer.showPlayer(plugin, target);
            else viewer.hidePlayer(plugin, target);
        }
    }

    private void reapplyLater(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) applyForViewer(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // New clients must not see staff who vanished before they connected.
        applyForViewer(event.getPlayer());
        reapplyLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!vanished.remove(player.getUniqueId())) return;
        // They already 'left' when enabling vanish; avoid a second leave message.
        event.setQuitMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        if (isVanished(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = event.getPlayer();
                if (!target.isOnline() || !isVanished(target.getUniqueId())) return;
                for (Player viewer : Bukkit.getOnlinePlayers()) applyForViewer(viewer);
            });
        } else reapplyLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reapplyLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("vanish.block-chat", true)
                || !isVanished(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().sendMessage(
                ChatColor.YELLOW + "Public chat is blocked while vanished. Use /vanish to return."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (plugin.getConfig().getBoolean("vanish.block-pickup", true)
                && event.getEntity() instanceof Player player && isVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getConfig().getBoolean("vanish.block-interactions", true)
                && isVanished(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getConfig().getBoolean("vanish.block-interactions", true)
                && isVanished(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (plugin.getConfig().getBoolean("vanish.block-interactions", true)
                && isVanished(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getConfig().getBoolean("vanish.block-interactions", true)
                && isVanished(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.getConfig().getBoolean("vanish.block-interactions", true)
                && isVanished(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("vanish.block-combat", true)) return;
        if (event.getEntity() instanceof Player player && isVanished(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.getDamager() instanceof Player attacker && isVanished(attacker.getUniqueId())) {
            event.setCancelled(true);
        } else if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player attacker && isVanished(attacker.getUniqueId())) event.setCancelled(true);
        }
    }

    public void shutdown() {
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!isVanished(target.getUniqueId())) continue;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.getUniqueId().equals(target.getUniqueId())) viewer.showPlayer(plugin, target);
            }
        }
        vanished.clear();
    }
}
