package dev.playerinspector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Value object for a versioned, write-once backup. No Bukkit dependencies. */
public record BackupRevision(String id, String kind, long createdAt, String transactionId) {
    public BackupRevision(String id, String kind, long createdAt) {
        this(id, kind, createdAt, null);
    }
    private static final Pattern NAME = Pattern.compile("(\\d{13})-(manual|safety)-([a-f0-9]{32})(?:-tx-([a-f0-9]{32}))?\\.yml");
    public static final Comparator<BackupRevision> NEWEST_FIRST = Comparator
            .comparingLong(BackupRevision::createdAt).reversed()
            .thenComparing(BackupRevision::id, Comparator.reverseOrder());

    public BackupRevision {
        if (!kind.equals("manual") && !kind.equals("safety"))
            throw new IllegalArgumentException("Unknown backup kind: " + kind);
        if (!(id.matches("[a-f0-9]{32}") || id.equals("legacy-" + kind)))
            throw new IllegalArgumentException("Invalid backup ID");
        if (createdAt < 0) throw new IllegalArgumentException("Invalid backup timestamp");
        if (transactionId != null && !transactionId.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("Invalid restore transaction ID");
    }

    public static BackupRevision create(String kind, long timestamp, String transactionId) {
        return new BackupRevision(UUID.randomUUID().toString().replace("-", ""), kind, timestamp, transactionId);
    }

    public static BackupRevision parse(String filename) {
        Matcher m = NAME.matcher(filename);
        if (!m.matches()) return null;
        try { return new BackupRevision(m.group(3), m.group(2), Long.parseLong(m.group(1)), m.group(4)); }
        catch (NumberFormatException ex) { return null; }
    }

    public boolean legacy() { return id.startsWith("legacy-"); }

    public String filename() {
        if (legacy()) throw new IllegalStateException("Legacy backup uses original layout");
        return createdAt + "-" + kind + "-" + id
                + (transactionId == null ? "" : "-tx-" + transactionId) + ".yml";
    }

    public String shortId() { return legacy() ? id : id.substring(0, 12); }

    /** Files to delete AFTER a successful new backup write. Never prunes legacy records. */
    public static List<BackupRevision> expired(List<BackupRevision> revisions, int manualLimit, int safetyLimit) {
        if (manualLimit < 1 || safetyLimit < 1) throw new IllegalArgumentException("Backup retention must be >= 1");
        List<BackupRevision> ordered = new ArrayList<>(revisions);
        ordered.sort(NEWEST_FIRST);
        Map<String, Integer> counts = new HashMap<>();
        List<BackupRevision> expired = new ArrayList<>();
        for (BackupRevision revision : ordered) {
            if (revision.legacy()) continue;
            int count = counts.merge(revision.kind(), 1, Integer::sum);
            if (count > (revision.kind().equals("manual") ? manualLimit : safetyLimit)) expired.add(revision);
        }
        return expired;
    }
}
