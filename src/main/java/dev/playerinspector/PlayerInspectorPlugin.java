package dev.playerinspector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerInspectorPlugin extends JavaPlugin implements Listener {
    private PlayerDataStore store;
    private InspectionMenus menus;
    private RemoteInventoryEditor editor;
    private RestoreService restorer;
    private VanishManager vanish;

    @Override
    public void onEnable() {
        String serverVersion = Bukkit.getBukkitVersion();
        if (!ServerVersion.supports(serverVersion)) {
            getLogger().severe("Unsupported Minecraft server version: " + serverVersion
                    + ". PlayerInspector supports Paper/Spigot 1.21.0 through 1.21.11 only.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        store = new PlayerDataStore(this);
        menus = new InspectionMenus(this);
        editor = new RemoteInventoryEditor(this);
        restorer = new RestoreService(this);
        vanish = new VanishManager(this);
        InspectCommand executor = new InspectCommand(this);
        PluginCommand command = getCommand("playerinspect");
        if (command == null) throw new IllegalStateException("Missing playerinspect in plugin.yml");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        PluginCommand vanishCommand = getCommand("vanish");
        if (vanishCommand == null) throw new IllegalStateException("Missing vanish in plugin.yml");
        vanishCommand.setExecutor(vanish);
        vanishCommand.setTabCompleter(vanish);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(editor, this);
        Bukkit.getPluginManager().registerEvents(vanish, this);

        for (Player player : Bukkit.getOnlinePlayers()) store.saveSnapshot(player);
        long every = Math.max(1200L, getConfig().getLong("snapshot-interval-ticks", 6000L));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) store.saveSnapshot(player);
        }, every, every);
        Bukkit.getScheduler().runTaskTimer(this, editor::refreshActive, 20L, 20L);
        getLogger().info("PlayerInspector enabled on " + serverVersion
                + ": live edits, last-death snapshots, safe restores.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Capture before respawn clears the inventory; don't touch getDrops() or keep-inventory rules.
        store.recordDeath(player.getUniqueId(), new LastDeath(DeathRecord.capture(event),
                PlayerSnapshot.fromPlayer(player), event.getKeepInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        store.saveSnapshot(event.getPlayer());
        editor.closeFor(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) store.saveSnapshot(player);
        }, 2L);
    }

    @Override
    public void onDisable() {
        if (vanish != null) vanish.shutdown();
        if (store == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) store.saveSnapshot(player);
        store.close();
    }

    /** One durable manual-backup entrypoint for commands and both types of inventory UI. */
    void createManualBackup(CommandSender actor, Player target) {
        if (!InspectCommand.isAuthorized(actor)
                || (actor instanceof Player player && !RemoteInventoryEditor.canEdit(player))) {
            actor.sendMessage(ChatColor.RED + "Making backups requires OP and playerinspector.edit.");
            return;
        }
        if (target == null || !target.isOnline()) {
            actor.sendMessage(ChatColor.RED + "Player is offline; no manual backup was made.");
            return;
        }
        PlayerSnapshot detached = PlayerSnapshot.fromPlayer(target);
        String actorId = actor instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
        actor.sendMessage(ChatColor.YELLOW + "Writing versioned manual backup for " + target.getName() + "...");
        store.saveBackupDurably(target.getUniqueId(), detached, "manual", actorId, "manual", null)
                .whenComplete((revision, failure) -> {
                    try {
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (failure != null || revision == null) {
                                actor.sendMessage(ChatColor.RED + "Backup FAILED. Inventory was not changed.");
                                return;
                            }
                            store.audit("BACKUP_MANUAL id=" + revision.id() + " actor=" + actorId
                                    + " target=" + target.getUniqueId());
                            actor.sendMessage(ChatColor.GREEN + "Manual backup saved for " + detached.name()
                                    + ". ID: " + revision.id());
                        });
                    } catch (RuntimeException ex) {
                        getLogger().warning("Could not report completed manual backup: " + ex.getMessage());
                    }
                });
    }

    PlayerDataStore store() { return store; }
    InspectionMenus menus() { return menus; }
    RemoteInventoryEditor editor() { return editor; }
    RestoreService restorer() { return restorer; }
    VanishManager vanish() { return vanish; }
}
