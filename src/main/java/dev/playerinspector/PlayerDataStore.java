package dev.playerinspector;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns all persistence. Serialize Bukkit ItemStacks on the main thread; disk operations use one writer. */
public final class PlayerDataStore {
    private final JavaPlugin plugin;
    private final Path snapshots;
    private final Path latestDeaths;
    private final Path oldDeaths;
    private final Path backups;
    private final Path transactions;
    private final Path auditLog;
    private final int keepManual;
    private final int keepSafety;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PlayerInspector-storage");
        thread.setDaemon(false);
        return thread;
    });

    private final Map<UUID, PlayerSnapshot> recentSnapshots = limitedMap();
    private final Map<UUID, LastDeath> recentLastDeaths = limitedMap();

    private static <T> Map<UUID, T> limitedMap() {
        return new LinkedHashMap<>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<UUID, T> eldest) {
                return size() > 256;
            }
        };
    }

    public PlayerDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        Path root = plugin.getDataFolder().toPath();
        snapshots = root.resolve("snapshots");
        latestDeaths = root.resolve("latest-death");
        oldDeaths = root.resolve("deaths");
        backups = root.resolve("backups");
        transactions = root.resolve("restore-transactions");
        auditLog = root.resolve("admin-actions.log");
        keepManual = Math.max(1, plugin.getConfig().getInt("backups.keep-manual", 10));
        keepSafety = Math.max(1, plugin.getConfig().getInt("backups.keep-safety", 20));
        try {
            Files.createDirectories(snapshots);
            Files.createDirectories(latestDeaths);
            Files.createDirectories(backups);
            Files.createDirectories(transactions);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create PlayerInspector data directories", ex);
        }
    }

    public void saveSnapshot(Player player) {
        saveSnapshot(player.getUniqueId(), PlayerSnapshot.fromPlayer(player));
    }

    public void saveSnapshot(UUID uuid, PlayerSnapshot snapshot) {
        recentSnapshots.put(uuid, snapshot);
        YamlConfiguration config = new YamlConfiguration();
        writeSnapshot(config, "", snapshot);
        write(snapshots.resolve(uuid + ".yml"), config.saveToString());
    }

    public PlayerSnapshot getSnapshot(UUID uuid) {
        PlayerSnapshot cached = recentSnapshots.get(uuid);
        if (cached != null) return cached;
        YamlConfiguration config = load(snapshots.resolve(uuid + ".yml"));
        if (config == null) return null;
        PlayerSnapshot snapshot = readSnapshot(config, "");
        if (snapshot != null) recentSnapshots.put(uuid, snapshot);
        return snapshot;
    }

    public void recordDeath(UUID uuid, LastDeath record) {
        recentLastDeaths.put(uuid, record);
        YamlConfiguration config = new YamlConfiguration();
        DeathRecord d = record.details();
        config.set("death.time", d.time());
        config.set("death.cause", d.cause());
        config.set("death.attacker", d.attacker());
        config.set("death.direct", d.directEntity());
        config.set("death.world", d.world());
        config.set("death.x", d.x());
        config.set("death.y", d.y());
        config.set("death.z", d.z());
        config.set("death.message", d.message());
        config.set("keep-inventory", record.keepInventory());
        if (record.inventory() != null) writeSnapshot(config, "items.", record.inventory());
        write(latestDeaths.resolve(uuid + ".yml"), config.saveToString());
    }

    public LastDeath getLastDeath(UUID uuid) {
        LastDeath cached = recentLastDeaths.get(uuid);
        if (cached != null) return cached;
        YamlConfiguration config = load(latestDeaths.resolve(uuid + ".yml"));
        if (config != null && config.contains("death.time")) {
            DeathRecord d = new DeathRecord(config.getLong("death.time"),
                    config.getString("death.cause", "unknown"),
                    config.getString("death.attacker", "none"),
                    config.getString("death.direct", "none"),
                    config.getString("death.world", "unknown"),
                    config.getInt("death.x"), config.getInt("death.y"), config.getInt("death.z"),
                    config.getString("death.message", ""));
            LastDeath result = new LastDeath(d, config.contains("items.storage")
                    ? readSnapshot(config, "items.") : null, config.getBoolean("keep-inventory"));
            recentLastDeaths.put(uuid, result);
            return result;
        }
        // 1.0.0 legacy history had only death metadata.
        YamlConfiguration old = load(oldDeaths.resolve(uuid + ".yml"));
        if (old != null) {
            List<?> rows = old.getList("history");
            if (rows != null && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> first) {
                LastDeath legacy = new LastDeath(DeathRecord.fromMap(first), null, false);
                recentLastDeaths.put(uuid, legacy);
                return legacy;
            }
        }
        return null;
    }

    /** Capture the detached PlayerSnapshot on Bukkit's server thread. The returned future signals disk completion. */
    public CompletableFuture<BackupRevision> saveBackupDurably(UUID uuid, PlayerSnapshot snapshot, String kind,
                                                               String actor, String reason, String transactionId) {
        BackupRevision revision = BackupRevision.create(kind, System.currentTimeMillis(), transactionId);
        YamlConfiguration config = new YamlConfiguration();
        config.set("backup-format", 2);
        config.set("backup-id", revision.id());
        config.set("backup-kind", revision.kind());
        config.set("backup-created-at", revision.createdAt());
        config.set("backup-reason", singleLine(reason));
        config.set("backup-actor", singleLine(actor));
        if (transactionId != null) config.set("restore-transaction-id", transactionId);
        writeSnapshot(config, "", snapshot);
        String content = config.saveToString();
        Path directory = backups.resolve(uuid.toString());
        Path destination = directory.resolve(revision.filename());
        return CompletableFuture.supplyAsync(() -> {
            try {
                writeImmutable(destination, content);
                pruneBackups(uuid, revision.id());
                return revision;
            } catch (IOException ex) {
                plugin.getLogger().severe("Backup " + revision.shortId() + " was NOT saved: " + ex);
                throw new CompletionException(ex);
            }
        }, writer);
    }

    /** Metadata only: bounded by configured retention. Legacy backups remain accessible but are never pruned. */
    public List<BackupRevision> listBackups(UUID uuid) {
        List<BackupRevision> revisions = new ArrayList<>();
        Path directory = backups.resolve(uuid.toString());
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
                for (Path file : stream) {
                    BackupRevision revision = BackupRevision.parse(file.getFileName().toString());
                    if (revision != null && Files.isRegularFile(file)) revisions.add(revision);
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not list backups for " + uuid + ": " + ex);
            }
        }
        for (String kind : List.of("manual", "safety")) {
            Path legacy = legacyPath(uuid, kind);
            if (Files.isRegularFile(legacy)) {
                try {
                    revisions.add(new BackupRevision("legacy-" + kind, kind,
                            Files.getLastModifiedTime(legacy).toMillis()));
                } catch (IOException ex) {
                    plugin.getLogger().warning("Cannot stat legacy backup " + legacy + ": " + ex);
                }
            }
        }
        revisions.sort(BackupRevision.NEWEST_FIRST);
        return List.copyOf(revisions);
    }

    /** Safety backups for these transactions must not be deleted automatically before an outcome is recorded. */
    public List<BackupRevision> unresolvedRestores(UUID uuid) {
        List<BackupRevision> result = new ArrayList<>();
        for (BackupRevision backup : listBackups(uuid)) {
            if (backup.transactionId() != null
                    && !Files.isRegularFile(outcomePath(uuid, backup.transactionId()))) result.add(backup);
        }
        return result;
    }

    public BackupRevision findBackup(UUID uuid, String kindOrId) {
        List<BackupRevision> matches = new ArrayList<>();
        boolean kind = kindOrId.equals("manual") || kindOrId.equals("safety");
        if (!kind && !kindOrId.matches("[a-f0-9]{8,32}|legacy-(manual|safety)")) return null;
        for (BackupRevision revision : listBackups(uuid)) {
            if (kind ? revision.kind().equals(kindOrId) : revision.id().startsWith(kindOrId)) {
                matches.add(revision);
            }
        }
        if (matches.isEmpty()) return null;
        if (kind) return matches.get(0);
        // Prefixes with multiple matches must never silently select the wrong inventory.
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public PlayerSnapshot getBackup(UUID uuid, BackupRevision revision) {
        if (revision == null) return null;
        Path path = revision.legacy() ? legacyPath(uuid, revision.kind())
                : backups.resolve(uuid.toString()).resolve(revision.filename());
        YamlConfiguration yaml = load(path);
        if (yaml == null) return null;
        if (!revision.legacy() && (!revision.id().equals(yaml.getString("backup-id", ""))
                || !revision.kind().equals(yaml.getString("backup-kind", ""))
                || (revision.transactionId() != null && !revision.transactionId().equals(
                        yaml.getString("restore-transaction-id", ""))))) {
            plugin.getLogger().warning("Backup metadata mismatch: " + path.getFileName());
            return null;
        }
        return readSnapshot(yaml, "");
    }

    private Path legacyPath(UUID uuid, String kind) {
        return backups.resolve(uuid + "." + kind + ".yml");
    }

    private void pruneBackups(UUID uuid, String justSavedId) {
        for (BackupRevision stale : BackupRevision.expired(listBackups(uuid), keepManual, keepSafety)) {
            try {
                // A clock correction must never cause the just-committed backup to disappear.
                if (stale.id().equals(justSavedId)) continue;
                // Preserve prepared but unresolved restorations even after an unclean shutdown.
                if (stale.transactionId() != null
                        && !Files.isRegularFile(outcomePath(uuid, stale.transactionId()))) continue;
                Files.deleteIfExists(backups.resolve(uuid.toString()).resolve(stale.filename()));
            } catch (IOException ex) {
                // Retaining too many backups is safer than deleting the wrong one.
                plugin.getLogger().warning("Could not prune backup " + stale.id() + ": " + ex);
            }
        }
    }

    private Path outcomePath(UUID target, String txId) {
        return transactions.resolve(target.toString()).resolve(txId + ".txt");
    }

    /** Transaction result marker: absence after a prepared safety snapshot signals an ambiguous crash. */
    public CompletableFuture<Boolean> recordRestoreOutcome(UUID target, String txId, String outcome,
                                                             String backupId, String source, String scope) {
        if (!txId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid transaction id");
        String line = "transaction=" + txId + "\n" + "outcome=" + singleLine(outcome) + "\n"
                + "safety-backup=" + singleLine(backupId) + "\nsource=" + singleLine(source)
                + "\nscope=" + singleLine(scope) + "\ncompleted=" + Instant.now() + "\n";
        Path destination = outcomePath(target, txId);
        return CompletableFuture.supplyAsync(() -> {
            boolean saved = writeNow(destination, line);
            if (saved) pruneBackups(target, "");
            return saved;
        }, writer);
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').replace('=', '_');
    }

    private static void writeSnapshot(YamlConfiguration yaml, String prefix, PlayerSnapshot snapshot) {
        yaml.set(prefix + "name", snapshot.name());
        yaml.set(prefix + "saved-at", snapshot.savedAt());
        yaml.set(prefix + "storage", Arrays.asList(snapshot.storage()));
        yaml.set(prefix + "armor", Arrays.asList(snapshot.armor()));
        yaml.set(prefix + "offhand", snapshot.offhand());
        yaml.set(prefix + "ender-chest", Arrays.asList(snapshot.enderChest()));
    }

    /** Fail closed on corrupt/incomplete YAML: never turn a malformed backup into an empty inventory. */
    private PlayerSnapshot readSnapshot(YamlConfiguration yaml, String prefix) {
        List<?> storage = yaml.getList(prefix + "storage");
        List<?> armor = yaml.getList(prefix + "armor");
        List<?> chest = yaml.getList(prefix + "ender-chest");
        if (storage == null || storage.size() != 36 || armor == null || armor.size() != 4
                || chest == null || chest.isEmpty() || chest.size() % 9 != 0
                || !validItems(storage) || !validItems(armor) || !validItems(chest)) {
            plugin.getLogger().warning("Rejected malformed/incomplete inventory snapshot");
            return null;
        }
        return new PlayerSnapshot(yaml.getString(prefix + "name", "Unknown"),
                yaml.getLong(prefix + "saved-at"), readItems(storage), readItems(armor),
                yaml.getItemStack(prefix + "offhand"), readItems(chest));
    }

    private static boolean validItems(List<?> entries) {
        for (Object entry : entries) if (entry != null && !(entry instanceof ItemStack)) return false;
        return true;
    }

    private static ItemStack[] readItems(List<?> list) {
        ItemStack[] result = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof ItemStack item) result[i] = item.clone();
        }
        return result;
    }

    public void audit(String action) {
        String line = Instant.now() + " " + singleLine(action) + System.lineSeparator();
        writer.execute(() -> {
            try {
                Files.writeString(auditLog, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not append admin audit log: " + ex);
            }
        });
    }

    private YamlConfiguration load(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
            return yaml;
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not read " + path.getFileName() + ": " + ex.getMessage());
            return null;
        }
    }

    private void write(Path destination, String text) {
        writer.execute(() -> writeNow(destination, text));
    }

    private boolean writeNow(Path destination, String text) {
        try {
            AtomicFiles.replace(destination, text);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save " + destination.getFileName() + ": " + ex);
            return false;
        }
    }

    private void writeImmutable(Path destination, String text) throws IOException {
        AtomicFiles.createNew(destination, text);
    }

    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(8, TimeUnit.SECONDS))
                plugin.getLogger().severe("Pending PlayerInspector file writes did not finish before shutdown");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("Interrupted while saving PlayerInspector data");
        }
    }
}
