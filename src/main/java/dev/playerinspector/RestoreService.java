package dev.playerinspector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Two-stage restore. Per-target serialization and durable, identifiable pre-restore snapshots. */
public final class RestoreService {
    private final PlayerInspectorPlugin plugin;
    private final Map<String, Pending> approvals = new HashMap<>();
    private final Set<UUID> inFlight = new HashSet<>();

    private record Source(String id, String label, PlayerSnapshot snapshot, long version) {}
    private record Pending(UUID target, String sourceId, String sourceLabel, String scope,
                           long sourceVersion, PlayerSnapshot original, long expiry) {}

    public RestoreService(PlayerInspectorPlugin plugin) { this.plugin = plugin; }

    private static String key(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    }

    public boolean isAuthorized(CommandSender sender) {
        return InspectCommand.isAuthorized(sender) &&
                (sender instanceof ConsoleCommandSender || sender.hasPermission("playerinspector.restore"));
    }

    private Source getSource(UUID target, String input) {
        if (input.equals("death")) {
            LastDeath death = plugin.store().getLastDeath(target);
            return death == null || death.inventory() == null ? null :
                    new Source("death", "death", death.inventory(), death.details().time());
        }
        BackupRevision revision = plugin.store().findBackup(target, input);
        if (revision == null) return null;
        PlayerSnapshot snapshot = plugin.store().getBackup(target, revision);
        return snapshot == null ? null : new Source(revision.id(), revision.kind() + " " + revision.shortId(),
                snapshot, snapshot.savedAt());
    }

    public void request(CommandSender admin, Player target, String source, String scope) {
        if (!isAuthorized(admin)) {
            admin.sendMessage(ChatColor.RED + "You must be OP with playerinspector.restore.");
            return;
        }
        if (!scope.equals("inventory") && !scope.equals("ender") && !scope.equals("all")) {
            admin.sendMessage(ChatColor.RED + "Scope must be inventory, ender, or all.");
            return;
        }
        if (inFlight.contains(target.getUniqueId())) {
            admin.sendMessage(ChatColor.RED + "Another restore is in progress for this player.");
            return;
        }
        Source selected = getSource(target.getUniqueId(), source);
        if (selected == null) {
            admin.sendMessage(ChatColor.RED + "No valid snapshot for '" + source
                    + "'. Use /pi " + target.getName() + " backups to see IDs.");
            return;
        }
        if (!scope.equals("inventory") && !selected.snapshot().fitsEnderChest(target.getEnderChest().getSize())) {
            admin.sendMessage(ChatColor.RED + "Saved ender items exceed current chest size; restore cancelled.");
            return;
        }
        approvals.put(key(admin), new Pending(target.getUniqueId(), selected.id(), selected.label(), scope,
                selected.version(), selected.snapshot(), System.currentTimeMillis() + 60_000L));
        admin.sendMessage(ChatColor.GOLD + "Restore " + scope + " for " + target.getName() + " from "
                + selected.label() + " (" + TextUtil.date(selected.snapshot().savedAt()) + ")?");
        admin.sendMessage(ChatColor.RED + "This REPLACES current items. Previously dropped/recovered items may be duplicated.");
        admin.sendMessage(ChatColor.YELLOW + "Confirm within 60 seconds: /pi " + target.getName() + " confirm");
    }

    public void confirm(CommandSender admin, Player target) {
        if (!isAuthorized(admin)) {
            admin.sendMessage(ChatColor.RED + "You must be OP with playerinspector.restore.");
            return;
        }
        Pending pending = approvals.remove(key(admin));
        if (pending == null || !pending.target().equals(target.getUniqueId())
                || pending.expiry() < System.currentTimeMillis()) {
            admin.sendMessage(ChatColor.RED + "No valid pending restore. Run /pi <player> restore ... first.");
            return;
        }
        UUID uuid = target.getUniqueId();
        if (!inFlight.add(uuid)) {
            admin.sendMessage(ChatColor.RED + "A different restore is already running for this player.");
            return;
        }
        Source selected = getSource(uuid, pending.sourceId());
        if (selected == null || selected.version() != pending.sourceVersion()
                || !pending.original().sameItems(selected.snapshot())) {
            inFlight.remove(uuid);
            admin.sendMessage(ChatColor.RED + "Restore source changed or disappeared. Please request again.");
            return;
        }
        if (!target.isOnline() || target.isDead() || PlayerSnapshot.cloneOrNull(target.getItemOnCursor()) != null) {
            inFlight.remove(uuid);
            admin.sendMessage(ChatColor.RED + "Target must be alive, online and have an empty inventory cursor.");
            return;
        }
        if (!pending.scope().equals("inventory") && !selected.snapshot().fitsEnderChest(target.getEnderChest().getSize())) {
            inFlight.remove(uuid);
            admin.sendMessage(ChatColor.RED + "Saved ender items exceed current chest size.");
            return;
        }

        PlayerSnapshot before = PlayerSnapshot.fromPlayer(target);
        String txId = UUID.randomUUID().toString().replace("-", "");
        String actor = admin instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        admin.sendMessage(ChatColor.YELLOW + "Saving immutable safety backup before restoring (tx "
                + txId.substring(0, 12) + ")...");
        plugin.store().saveBackupDurably(uuid, before, "safety", actor, "pre-restore", txId)
                .whenComplete((revision, error) -> {
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                if (error != null || revision == null) {
                                    admin.sendMessage(ChatColor.RED + "Restore CANCELLED: pre-restore backup failed to write.");
                                    return;
                                }
                                if (!target.isOnline() || target.isDead() || !isAuthorized(admin)
                                        || (admin instanceof Player operator && !operator.isOnline())) {
                                    aborted(admin, uuid, txId, revision, pending, "target-offline-dead-or-unauthorized");
                                    return;
                                }
                                if (PlayerSnapshot.cloneOrNull(target.getItemOnCursor()) != null
                                        || !before.sameItems(PlayerSnapshot.fromPlayer(target))) {
                                    aborted(admin, uuid, txId, revision, pending, "inventory-changed");
                                    return;
                                }
                                if (!pending.scope().equals("inventory")
                                        && !selected.snapshot().fitsEnderChest(target.getEnderChest().getSize())) {
                                    aborted(admin, uuid, txId, revision, pending, "ender-capacity-changed");
                                    return;
                                }
                                try {
                                    selected.snapshot().applyTo(target, pending.scope());
                                } catch (RuntimeException failure) {
                                    plugin.getLogger().severe("Restore tx " + txId + " failed: " + failure);
                                    try {
                                        before.applyTo(target, "all");
                                    } catch (RuntimeException rollbackFailure) {
                                        plugin.getLogger().severe("Rollback ALSO FAILED for tx " + txId
                                                + ". Use safety backup " + revision.id() + ": " + rollbackFailure);
                                        aborted(admin, uuid, txId, revision, pending, "rollback-failed");
                                        admin.sendMessage(ChatColor.RED + "URGENT: rollback failed. Pre-restore backup ID: "
                                                + revision.id());
                                        return;
                                    }
                                    aborted(admin, uuid, txId, revision, pending, "restore-failed-rolled-back");
                                    return;
                                }
                                plugin.store().saveSnapshot(target);
                                plugin.store().audit("RESTORE_APPLIED tx=" + txId + " actor=" + actor
                                        + " target=" + uuid + " source=" + pending.sourceId()
                                        + " scope=" + pending.scope() + " safety=" + revision.id());
                                recordOutcome(uuid, txId, "APPLIED", revision.id(), pending);
                                admin.sendMessage(ChatColor.GREEN + "Restored " + pending.scope() + " for "
                                        + target.getName() + ". Safety backup: " + revision.id()
                                        + " | Transaction: " + txId);
                                if (admin instanceof Player p && p.isOnline()) plugin.menus().openOverview(p, target);
                            } finally {
                                inFlight.remove(uuid);
                            }
                        });
                    } catch (RuntimeException failure) {
                        plugin.getLogger().severe("Restore tx " + txId
                                + " could not schedule on main thread. No inventory changes applied: " + failure);
                        // Plugin is normally disabled when scheduling is rejected; inFlight is no longer used.
                    }
                });
    }

    private void aborted(CommandSender admin, UUID target, String txId, BackupRevision revision,
                         Pending pending, String reason) {
        admin.sendMessage(ChatColor.RED + "Restore stopped (" + reason + "). Check the target inventory before retrying."
                + " Safety backup: " + revision.id());
        plugin.store().audit("RESTORE_ABORTED tx=" + txId + " target=" + target + " reason=" + reason);
        recordOutcome(target, txId, "ABORTED-" + reason, revision.id(), pending);
    }

    private void recordOutcome(UUID target, String txId, String status, String backupId, Pending pending) {
        plugin.store().recordRestoreOutcome(target, txId, status, backupId,
                        pending.sourceId(), pending.scope())
                .whenComplete((ok, error) -> {
                    if (error != null || !Boolean.TRUE.equals(ok))
                        plugin.getLogger().severe("Could not save restore outcome for tx " + txId
                                + ". Inspect backup and player inventory before retrying.");
                });
    }
}
