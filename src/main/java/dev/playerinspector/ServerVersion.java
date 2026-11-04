package dev.playerinspector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only the Minecraft 1.21 release line is supported (1.21.0 through 1.21.11).
 * Bukkit reports 1.21.0 as 1.21-R0.1-SNAPSHOT; later releases include the patch.
 * Keep this independent of Bukkit to make version parsing straightforward to test.
 */
final class ServerVersion {
    private static final Pattern MINECRAFT_121 = Pattern.compile("^1\\.21(?:\\.(0|[1-9][0-9]*))?(?=-R|$)");
    private ServerVersion() {}

    static boolean supports(String bukkitVersion) {
        if (bukkitVersion == null) return false;
        Matcher match = MINECRAFT_121.matcher(bukkitVersion);
        if (!match.find()) return false;
        String patch = match.group(1);
        if (patch == null) return true; // Spigot calls 1.21.0 simply 1.21
        try {
            int value = Integer.parseInt(patch);
            return value >= 0 && value <= 11;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
