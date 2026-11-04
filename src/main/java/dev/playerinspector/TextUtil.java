package dev.playerinspector;

import org.bukkit.ChatColor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class TextUtil {
    private TextUtil() {}

    private static final DateTimeFormatter UTC = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC);

    public static String date(long millis) {
        return millis <= 0 ? "Unknown" : UTC.format(Instant.ofEpochMilli(millis));
    }

    public static String playTime(long ticks) {
        long seconds = Math.max(0L, ticks) / 20L;
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        return days > 0 ? days + "d " + hours + "h " + minutes + "m"
                : hours + "h " + minutes + "m";
    }

    public static String readable(String input) {
        if (input == null || input.isBlank()) return "Unknown";
        String text = input.contains(":") ? input.substring(input.indexOf(':') + 1) : input;
        return text.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    public static String singleLine(String input, int maxChars) {
        if (input == null) return "";
        String cleaned = ChatColor.stripColor(input.replace('\n', ' ').replace('\r', ' '));
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars - 1) + "…" : cleaned;
    }
}
