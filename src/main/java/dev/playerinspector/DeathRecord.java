package dev.playerinspector;

import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** A death recorded while this plugin is installed. Never inferred for earlier deaths. */
public record DeathRecord(long time, String cause, String attacker, String directEntity,
                          String world, int x, int y, int z, String message) {

    public static DeathRecord capture(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Location loc = victim.getLocation();
        DamageSource source = event.getDamageSource();
        return new DeathRecord(System.currentTimeMillis(),
                source.getDamageType().getKey().toString(),
                entityName(source.getCausingEntity()),
                entityName(source.getDirectEntity()),
                loc.getWorld() == null ? "unknown" : loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                clean(event.getDeathMessage()));
    }

    private static String entityName(Entity entity) {
        if (entity == null) return "none";
        if (entity instanceof Player player) return clean(player.getName());
        return entity.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').replace('§', ' ').strip();
    }

    public String location() {
        return world + " [" + x + ", " + y + ", " + z + "]";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", time);
        map.put("cause", cause);
        map.put("attacker", attacker);
        map.put("direct", directEntity);
        map.put("world", world);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("message", message);
        return map;
    }

    public static DeathRecord fromMap(Map<?, ?> map) {
        return new DeathRecord(longValue(map.get("time")), str(map.get("cause")),
                str(map.get("attacker")), str(map.get("direct")),
                str(map.get("world")), intValue(map.get("x")),
                intValue(map.get("y")), intValue(map.get("z")), str(map.get("message")));
    }

    private static String str(Object value) { return value == null ? "unknown" : String.valueOf(value); }
    private static long longValue(Object val) { return val instanceof Number n ? n.longValue() : 0L; }
    private static int intValue(Object val) { return val instanceof Number n ? n.intValue() : 0; }
}
