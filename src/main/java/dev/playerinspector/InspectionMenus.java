package dev.playerinspector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Inspection and death previews are detached read-only views, separate from live editing. */
public final class InspectionMenus implements Listener {
    private final PlayerInspectorPlugin plugin;

    public InspectionMenus(PlayerInspectorPlugin plugin) { this.plugin = plugin; }

    private record Session(OfflinePlayer target, PlayerSnapshot snapshot, String source, boolean live) {
        String name() { return InspectCommand.targetName(target); }
    }

    private static final class InspectHolder implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        InspectHolder(Player viewer, int size, String title) {
            owner = viewer.getUniqueId();
            inventory = Bukkit.createInventory(this, size, title);
        }
        @Override public Inventory getInventory() { return inventory; }
        void put(int slot, ItemStack stack) { inventory.setItem(slot, stack); }
        void button(int slot, ItemStack stack, Consumer<Player> action) {
            put(slot, stack);
            actions.put(slot, action);
        }
    }

    private Session current(OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online != null && online.isOnline())
            return new Session(target, PlayerSnapshot.fromPlayer(online), "Live copy", true);
        return new Session(target, plugin.store().getSnapshot(target.getUniqueId()), "Offline snapshot", false);
    }

    public void openOverview(Player admin, OfflinePlayer target) {
        if (authorized(admin)) showOverview(admin, current(target));
    }

    public void openInventory(Player admin, OfflinePlayer target) {
        if (!authorized(admin)) return;
        Session current = current(target);
        if (hasSnapshot(admin, current)) showInventory(admin, current);
    }

    public void openEnderChest(Player admin, OfflinePlayer target) {
        if (!authorized(admin)) return;
        Session current = current(target);
        if (hasSnapshot(admin, current)) showEnder(admin, current, 0);
    }

    public void openDeath(Player admin, OfflinePlayer target) {
        if (authorized(admin)) showDeath(admin, current(target));
    }

    /** Direct read-only browser for all shulker boxes or bundles in a snapshot. */
    public void openContainers(Player admin, OfflinePlayer target, boolean shulkers, String location) {
        if (!authorized(admin)) return;
        Session session;
        if (location.equals("death")) {
            LastDeath last = plugin.store().getLastDeath(target.getUniqueId());
            if (last == null || last.inventory() == null) {
                admin.sendMessage(ChatColor.YELLOW + "No saved inventory from the most recent death.");
                return;
            }
            session = new Session(target, last.inventory(), "DEATH snapshot", false);
        } else session = current(target);
        if (!hasSnapshot(admin, session)) return;
        List<ContainerEntry> containers = new ArrayList<>();
        PlayerSnapshot snapshot = session.snapshot();
        if (!location.equals("ender")) {
            ItemStack[] storage = snapshot.storage();
            for (int i = 0; i < storage.length; i++) {
                String slot = i < 9 ? "Hotbar " + (i + 1) : "Storage " + (i + 1);
                collectContainers(storage[i], slot, shulkers, 0, containers);
            }
            ItemStack[] armor = snapshot.armor();
            String[] armorNames = {"Boots", "Leggings", "Chestplate", "Helmet"};
            for (int i = 0; i < armor.length; i++)
                collectContainers(armor[i], armorNames[i], shulkers, 0, containers);
            collectContainers(snapshot.offhand(), "Offhand", shulkers, 0, containers);
        }
        if (!location.equals("inventory")) {
            ItemStack[] ender = snapshot.enderChest();
            for (int i = 0; i < ender.length; i++)
                collectContainers(ender[i], "Ender " + (i + 1), shulkers, 0, containers);
        }
        showContainerIndex(admin, session, containers, shulkers, location, 0);
    }

    private record ContainerEntry(ItemStack item, String path) { }

    /** Bounded search also finds containers nested inside other shulkers or bundles. */
    private void collectContainers(ItemStack item, String path, boolean wantShulkers,
                                   int depth, List<ContainerEntry> results) {
        if (item == null || item.getType().isAir() || results.size() >= 400 || depth > 5) return;
        ItemMeta meta = item.getItemMeta();
        boolean shulker = meta instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof ShulkerBox;
        boolean bundle = meta instanceof BundleMeta;
        if (wantShulkers ? shulker : bundle) results.add(new ContainerEntry(item.clone(), path));
        if ((!shulker && !bundle) || depth >= 5) return;
        try {
            ItemStack[] nested = readContainer(item);
            if (nested == null) return;
            for (int i = 0; i < nested.length && results.size() < 400; i++)
                collectContainers(nested[i], path + " > slot " + (i + 1), wantShulkers, depth + 1, results);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not inspect nested container in " + path + ": " + ex.getMessage());
        }
    }

    private void showContainerIndex(Player admin, Session session, List<ContainerEntry> entries,
                                    boolean shulkers, String location, int page) {
        int maxPage = entries.isEmpty() ? 0 : (entries.size() - 1) / 45;
        int currentPage = Math.max(0, Math.min(page, maxPage));
        InspectHolder gui = new InspectHolder(admin, 54, title(shulkers ? "Shulkers" : "Bundles", session.name()));
        if (entries.isEmpty()) {
            gui.put(22, icon(Material.BARRIER, ChatColor.YELLOW + "No "
                    + (shulkers ? "shulker boxes" : "bundles") + " found", "Source: " + location));
        }
        for (int i = currentPage * 45; i < Math.min(entries.size(), (currentPage + 1) * 45); i++) {
            ContainerEntry entry = entries.get(i);
            int slot = i % 45;
            ItemStack item = entry.item();
            gui.button(slot, inspectedItem(item, entry.path(), true), p -> showContainer(p, session, item,
                    gui, 1, 0));
        }
        gui.button(45, icon(Material.ARROW, ChatColor.YELLOW + "Overview"), p -> openOverview(p, session.target()));
        gui.put(49, icon(Material.PAPER, ChatColor.AQUA + (shulkers ? "Shulkers: " : "Bundles: ")
                + entries.size(), "Source: " + session.source() + " / " + location,
                "Page " + (currentPage + 1) + "/" + (maxPage + 1), "Read-only inventory snapshots"));
        if (currentPage > 0) gui.button(46, icon(Material.ARROW, ChatColor.GREEN + "Previous"),
                p -> showContainerIndex(p, session, entries, shulkers, location, currentPage - 1));
        if (currentPage < maxPage) gui.button(52, icon(Material.ARROW, ChatColor.GREEN + "Next"),
                p -> showContainerIndex(p, session, entries, shulkers, location, currentPage + 1));
        gui.button(53, icon(Material.BARRIER, ChatColor.RED + "Close"), Player::closeInventory);
        admin.openInventory(gui.getInventory());
    }

    private static boolean authorized(Player player) {
        if (InspectCommand.isAuthorized(player)) return true;
        player.sendMessage(ChatColor.RED + "Only server operators may inspect players.");
        return false;
    }

    private static boolean hasSnapshot(Player admin, Session session) {
        if (session.snapshot() != null) return true;
        admin.sendMessage(ChatColor.YELLOW + "No saved " + session.source() + " for " + session.name());
        return false;
    }

    private static String title(String section, String target) {
        return ChatColor.DARK_AQUA + section + ": " + TextUtil.singleLine(target, 16);
    }

    private void showOverview(Player admin, Session session) {
        InspectHolder gui = new InspectHolder(admin, 27, title("Inspector", session.name()));
        long ticks = Integer.toUnsignedLong(session.target().getStatistic(Statistic.PLAY_ONE_MINUTE));
        int deaths = session.target().getStatistic(Statistic.DEATHS);
        LastDeath lastDeath = plugin.store().getLastDeath(session.target().getUniqueId());
        gui.put(4, icon(Material.PLAYER_HEAD, ChatColor.AQUA + session.name(),
                session.live() ? "Player ONLINE" : "Player OFFLINE",
                session.snapshot() == null ? "Inventory not saved yet" : session.source() + " " + TextUtil.date(session.snapshot().savedAt())));
        gui.put(10, icon(Material.CLOCK, ChatColor.GOLD + "Total playtime", TextUtil.playTime(ticks)));
        gui.put(11, icon(Material.SKELETON_SKULL, ChatColor.RED + "Lifetime deaths: " + deaths,
                lastDeath == null ? "No recorded death details" : "Latest: " + TextUtil.date(lastDeath.details().time())));
        if (session.snapshot() != null) {
            gui.button(12, icon(Material.CHEST, ChatColor.GREEN + "View inventory",
                    "Storage, hotbar, armor, offhand", "Inspect durability and nested containers"), p -> showInventory(p, session));
            gui.button(13, icon(Material.ENDER_CHEST, ChatColor.GREEN + "View ender chest"), p -> showEnder(p, session, 0));
        }
        if (session.snapshot() != null) {
            gui.button(22, icon(Material.SHULKER_BOX, ChatColor.GREEN + "Browse shulker boxes"),
                    p -> openContainers(p, session.target(), true, "all"));
            gui.button(23, icon(Material.BUNDLE, ChatColor.GREEN + "Browse bundles"),
                    p -> openContainers(p, session.target(), false, "all"));
        }
        gui.button(14, icon(Material.SKELETON_SKULL, ChatColor.YELLOW + "Latest death",
                "Time, cause, coordinates", "Inventory at moment of death"), p -> showDeath(p, session));
        Location vanilla = session.target().getLastDeathLocation();
        gui.put(15, icon(Material.COMPASS, ChatColor.YELLOW + "Vanilla last death location",
                vanilla == null ? "Not available" : location(vanilla)));
        gui.button(16, icon(Material.SLIME_BALL, ChatColor.GREEN + "Refresh"), p -> openOverview(p, session.target()));
        if (session.live() && RemoteInventoryEditor.canEdit(admin)) {
            gui.button(19, icon(Material.CHEST, ChatColor.GOLD + "EDIT live inventory",
                    "Take, move, and give items", "Automatic pre-edit safety backup"),
                    p -> plugin.editor().open(p, session.target().getPlayer(), false));
            gui.button(20, icon(Material.ENDER_CHEST, ChatColor.GOLD + "EDIT live ender chest",
                    "Take, move, and give items"),
                    p -> plugin.editor().open(p, session.target().getPlayer(), true));
            gui.button(21, icon(Material.PAPER, ChatColor.GREEN + "Create manual backup"), p -> {
                Player target = session.target().getPlayer();
                if (target != null && target.isOnline()) {
                    plugin.createManualBackup(p, target);
                }
            });
        }
        gui.button(26, icon(Material.BARRIER, ChatColor.RED + "Close"), Player::closeInventory);
        admin.openInventory(gui.getInventory());
    }

    private static String location(Location loc) {
        return (loc.getWorld() == null ? "unknown" : loc.getWorld().getName()) + " " +
                loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    private void showDeath(Player admin, Session current) {
        LastDeath last = plugin.store().getLastDeath(current.target().getUniqueId());
        InspectHolder gui = new InspectHolder(admin, 27, title("Last death", current.name()));
        if (last == null) {
            gui.put(4, icon(Material.BARRIER, ChatColor.YELLOW + "No death recorded by this plugin",
                    "Details and inventory become available after next death"));
        } else {
            DeathRecord d = last.details();
            gui.put(4, icon(Material.SKELETON_SKULL, ChatColor.RED + "Most recent death",
                    "When: " + TextUtil.date(d.time()), "Cause: " + TextUtil.readable(d.cause()),
                    "Attacker: " + d.attacker(), "Direct: " + d.directEntity(),
                    "Where: " + d.location(), "Keep inventory: " + last.keepInventory(),
                    d.message().isBlank() ? "" : TextUtil.singleLine(d.message(), 90)));
            if (last.inventory() != null) {
                Session atDeath = new Session(current.target(), last.inventory(), "DEATH snapshot", false);
                gui.button(10, icon(Material.CHEST, ChatColor.GREEN + "Inventory AT DEATH",
                        "Read-only view, with shulkers/bundles"), p -> showInventory(p, atDeath));
                gui.button(11, icon(Material.ENDER_CHEST, ChatColor.GREEN + "Ender chest AT DEATH"),
                        p -> showEnder(p, atDeath, 0));
                if (current.target().getPlayer() != null && plugin.restorer().isAuthorized(admin)) {
                    gui.button(13, icon(Material.PAPER, ChatColor.RED + "Request restore from death",
                            "Restores inventory, armor and offhand", "Requires separate /pi confirm command"), p -> {
                        Player target = current.target().getPlayer();
                        if (target != null && target.isOnline()) plugin.restorer().request(p, target, "death", "inventory");
                    });
                }
            } else {
                gui.put(10, icon(Material.BARRIER, ChatColor.YELLOW + "Death inventory not available",
                        "Older 1.0 record contains metadata only"));
            }
        }
        gui.button(18, icon(Material.ARROW, ChatColor.YELLOW + "Overview"), p -> openOverview(p, current.target()));
        gui.button(26, icon(Material.BARRIER, ChatColor.RED + "Close"), Player::closeInventory);
        admin.openInventory(gui.getInventory());
    }

    private void showInventory(Player admin, Session session) {
        if (!hasSnapshot(admin, session)) return;
        InspectHolder gui = new InspectHolder(admin, 54, title("Inventory", session.name()));
        gui.put(4, icon(Material.PAPER, ChatColor.AQUA + session.name() + " | " + session.source(),
                "Captured: " + TextUtil.date(session.snapshot().savedAt()),
                "READ ONLY: click shulker boxes and bundles"));
        ItemStack[] storage = session.snapshot().storage();
        ItemStack[] armor = session.snapshot().armor();
        for (int i = 0; i < 27; i++) putInspected(gui, 9 + i, storage[9 + i], "Storage " + (9 + i), session, 0);
        for (int i = 0; i < 9; i++) putInspected(gui, 36 + i, storage[i], "Hotbar " + (i + 1), session, 0);
        putInspected(gui, 45, armor[3], "Helmet", session, 0);
        putInspected(gui, 46, armor[2], "Chestplate", session, 0);
        putInspected(gui, 47, armor[1], "Leggings", session, 0);
        putInspected(gui, 48, armor[0], "Boots", session, 0);
        putInspected(gui, 49, session.snapshot().offhand(), "Offhand", session, 0);
        gui.button(50, icon(Material.ARROW, ChatColor.YELLOW + "Overview"), p -> openOverview(p, session.target()));
        gui.button(51, icon(Material.ENDER_CHEST, ChatColor.GREEN + "Ender chest"), p -> showEnder(p, session, 0));
        if (session.source().equals("Live copy") && session.target().getPlayer() != null && RemoteInventoryEditor.canEdit(admin)) {
            gui.button(52, icon(Material.CHEST, ChatColor.RED + "Edit LIVE inventory"),
                    p -> plugin.editor().open(p, session.target().getPlayer(), false));
        } else if (session.source().equals("Offline snapshot")) {
            gui.button(52, icon(Material.SLIME_BALL, ChatColor.GREEN + "Reload"), p -> openInventory(p, session.target()));
        }
        gui.button(53, icon(Material.BARRIER, ChatColor.RED + "Close"), Player::closeInventory);
        admin.openInventory(gui.getInventory());
    }

    private void showEnder(Player admin, Session session, int page) {
        if (!hasSnapshot(admin, session)) return;
        ItemStack[] contents = session.snapshot().enderChest();
        int maxPage = contents.length == 0 ? 0 : (contents.length - 1) / 45;
        int currentPage = Math.max(0, Math.min(maxPage, page));
        InspectHolder gui = new InspectHolder(admin, 54, title("Ender chest", session.name()));
        for (int i = 0; i < 45 && currentPage * 45 + i < contents.length; i++)
            putInspected(gui, i, contents[currentPage * 45 + i],
                    "Ender " + (currentPage * 45 + i + 1), session, 0);
        gui.button(45, icon(Material.ARROW, ChatColor.YELLOW + "Overview"), p -> openOverview(p, session.target()));
        gui.put(49, icon(Material.PAPER, ChatColor.AQUA + session.source() + " | Page " + (currentPage + 1)
                + "/" + (maxPage + 1), "Read-only copy"));
        if (currentPage > 0) gui.button(46, icon(Material.ARROW, ChatColor.GREEN + "Previous"), p -> showEnder(p, session, currentPage - 1));
        if (currentPage < maxPage) gui.button(52, icon(Material.ARROW, ChatColor.GREEN + "Next"), p -> showEnder(p, session, currentPage + 1));
        if (session.source().equals("Live copy") && session.target().getPlayer() != null && RemoteInventoryEditor.canEdit(admin)) {
            gui.button(51, icon(Material.CHEST, ChatColor.RED + "Edit LIVE ender chest"),
                    p -> plugin.editor().open(p, session.target().getPlayer(), true));
        }
        gui.button(53, icon(Material.BARRIER, ChatColor.RED + "Close"), Player::closeInventory);
        admin.openInventory(gui.getInventory());
    }

    private void putInspected(InspectHolder ui, int slot, ItemStack original, String name, Session session, int depth) {
        if (original == null || original.getType().isAir()) return;
        ItemStack display = inspectedItem(original, name, true);
        if (isContainer(original) && depth < 6) {
            ItemStack cloned = original.clone();
            ui.button(slot, display, p -> showContainer(p, session, cloned, ui, depth + 1, 0));
        } else ui.put(slot, display);
    }

    private void showContainer(Player admin, Session session, ItemStack item, InspectHolder back, int depth, int page) {
        if (depth > 6) return;
        ItemStack[] contents = readContainer(item);
        if (contents == null) return;
        int maxPage = contents.length == 0 ? 0 : (contents.length - 1) / 45;
        int currentPage = Math.max(0, Math.min(maxPage, page));
        InspectHolder gui = new InspectHolder(admin, 54, title("Contents", session.name()));
        for (int i = 0; i < 45 && currentPage * 45 + i < contents.length; i++)
            putInspected(gui, i, contents[currentPage * 45 + i],
                    "Container " + (currentPage * 45 + i + 1), session, depth);
        gui.button(45, icon(Material.ARROW, ChatColor.YELLOW + "Back"), p -> p.openInventory(back.getInventory()));
        gui.put(49, icon(Material.BOOK, ChatColor.AQUA + TextUtil.readable(item.getType().name()),
                "Page " + (currentPage + 1) + "/" + (maxPage + 1), "Nested containers are read-only"));
        if (currentPage > 0) gui.button(52, icon(Material.ARROW, ChatColor.GREEN + "Previous"),
                p -> showContainer(p, session, item, back, depth, currentPage - 1));
        if (currentPage < maxPage) gui.button(53, icon(Material.ARROW, ChatColor.GREEN + "Next"),
                p -> showContainer(p, session, item, back, depth, currentPage + 1));
        admin.openInventory(gui.getInventory());
    }

    private static boolean isContainer(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta) return true;
        return meta instanceof BlockStateMeta blockMeta && blockMeta.getBlockState() instanceof ShulkerBox;
    }

    private static ItemStack[] readContainer(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundle)
            return bundle.getItems().stream().map(PlayerSnapshot::cloneOrNull).toArray(ItemStack[]::new);
        if (meta instanceof BlockStateMeta blockMeta && blockMeta.getBlockState() instanceof ShulkerBox shulker)
            return PlayerSnapshot.copy(shulker.getSnapshotInventory().getContents(), 27);
        return null;
    }

    static ItemStack inspectedItem(ItemStack source, String slotName, boolean readOnly) {
        ItemStack copy = source.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(" ");
        lore.add(ChatColor.DARK_AQUA + "Inspector | " + slotName);
        if (meta instanceof Damageable damage && source.getType().getMaxDurability() > 0) {
            int max = source.getType().getMaxDurability();
            int remaining = Math.max(0, max - damage.getDamage());
            lore.add(ChatColor.GRAY + "Durability: " + ChatColor.WHITE + remaining + "/" + max
                    + " (" + Math.round(100.0 * remaining / max) + "%)");
        }
        if (meta.isUnbreakable()) lore.add(ChatColor.GRAY + "Unbreakable");
        if (isContainer(source)) lore.add(ChatColor.GREEN + (readOnly ? "Click to inspect contents" : "Use read-only view for contents"));
        lore.add(ChatColor.DARK_GRAY + (readOnly ? "Read only | Original item unchanged" : "LIVE editor | Left/Right click to transfer"));
        meta.setLore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private static ItemStack icon(Material type, String name, String... description) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            for (String line : description) if (line != null && !line.isBlank())
                lore.add(ChatColor.GRAY + TextUtil.singleLine(line, 115));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof InspectHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        Consumer<Player> action = holder.actions.get(event.getRawSlot());
        if (action == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && authorized(player)
                    && player.getOpenInventory().getTopInventory() == top) action.accept(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InspectHolder) event.setCancelled(true);
    }
}
