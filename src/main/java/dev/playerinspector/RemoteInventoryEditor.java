package dev.playerinspector;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Live remote inventory editor: mirrors actual slots, applies only explicit, guarded click transfers. */
public final class RemoteInventoryEditor implements Listener {
    private final PlayerInspectorPlugin plugin;
    private final Map<UUID, EditorHolder> sessions = new HashMap<>();
    private final Map<UUID, UUID> pendingOpenTokens = new HashMap<>();

    public RemoteInventoryEditor(PlayerInspectorPlugin plugin) { this.plugin = plugin; }

    private static final class EditorHolder implements InventoryHolder {
        private final UUID adminId;
        private final UUID targetId;
        private final boolean ender;
        private int page = 0;
        private final Inventory inventory;
        private final ItemStack[] lastShown = new ItemStack[54];

        EditorHolder(Player admin, Player target, boolean ender) {
            this.adminId = admin.getUniqueId();
            this.targetId = target.getUniqueId();
            this.ender = ender;
            String type = ender ? "Ender" : "Inventory";
            String name = TextUtil.singleLine(target.getName(), 16);
            inventory = Bukkit.createInventory(this, 54, ChatColor.RED + "Edit " + type + ": " + name);
        }

        @Override public Inventory getInventory() { return inventory; }

        int viewToReal(int viewSlot, Player target) {
            if (ender) {
                int at = viewSlot - 9 + page * 45;
                return viewSlot >= 9 && viewSlot < 54 && at < target.getEnderChest().getSize() ? at : -1;
            }
            if (viewSlot >= 9 && viewSlot <= 35) return viewSlot; // Storage slots 9..35
            if (viewSlot >= 36 && viewSlot <= 44) return viewSlot - 36; // Hotbar 0..8
            if (viewSlot >= 45 && viewSlot <= 48) return 39 - (viewSlot - 45); // helmet..boots
            return viewSlot == 49 ? 40 : -1; // Offhand
        }
    }

    public static boolean canEdit(Player admin) {
        return admin.isOp() && admin.hasPermission("playerinspector.use")
                && admin.hasPermission("playerinspector.edit");
    }

    public void open(Player admin, Player target, boolean ender) {
        if (!canEdit(admin)) {
            admin.sendMessage(ChatColor.RED + "Editing requires OP and playerinspector.edit.");
            return;
        }
        if (target == null || !target.isOnline()) {
            admin.sendMessage(ChatColor.RED + "Editing offline inventories is unavailable. Their saved snapshot is view-only.");
            return;
        }
        // Never offer an editable GUI until the pre-edit state exists on disk.
        // Each request has a token, so an older async completion cannot supersede a newer request.
        PlayerSnapshot before = PlayerSnapshot.fromPlayer(target);
        Inventory previousTop = admin.getOpenInventory().getTopInventory();
        UUID token = UUID.randomUUID();
        pendingOpenTokens.put(admin.getUniqueId(), token);
        admin.sendMessage(ChatColor.YELLOW + "Saving pre-edit safety snapshot before opening editor...");
        plugin.store().saveBackupDurably(target.getUniqueId(), before, "safety",
                        admin.getUniqueId().toString(), "edit-open", null)
                .whenComplete((revision, failure) -> {
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!token.equals(pendingOpenTokens.get(admin.getUniqueId()))) return;
                            pendingOpenTokens.remove(admin.getUniqueId());
                            if (failure != null || revision == null) {
                                admin.sendMessage(ChatColor.RED + "Cannot edit: safety backup was NOT saved.");
                                return;
                            }
                            if (!admin.isOnline() || !canEdit(admin) || !target.isOnline()
                                    || admin.getOpenInventory().getTopInventory() != previousTop
                                    || !before.sameItems(PlayerSnapshot.fromPlayer(target))) {
                                if (admin.isOnline()) admin.sendMessage(ChatColor.RED
                                        + "Editor opening cancelled: target changed, disconnected, or permission changed. Retry.");
                                return;
                            }
                            plugin.store().audit("EDIT_OPEN actor=" + admin.getUniqueId()
                                    + " target=" + target.getUniqueId() + " safety=" + revision.id()
                                    + " type=" + (ender ? "ender" : "inventory"));
                            EditorHolder holder = new EditorHolder(admin, target, ender);
                            refresh(holder, target);
                            sessions.put(admin.getUniqueId(), holder);
                            admin.openInventory(holder.getInventory());
                            admin.sendMessage(ChatColor.YELLOW + "Live editor: left-click take/place/swap; right-click half/single."
                                    + " Shift-clicks and drags to the upper inventory are blocked."
                                    + " Safety backup: " + revision.shortId());
                        });
                    } catch (RuntimeException ex) {
                        plugin.getLogger().warning("Editor failed to schedule after backup: " + ex.getMessage());
                    }
                });
    }

    private static ItemStack getReal(Player target, boolean ender, int index) {
        if (ender) return PlayerSnapshot.cloneOrNull(target.getEnderChest().getItem(index));
        if (index < 36) return PlayerSnapshot.cloneOrNull(target.getInventory().getStorageContents()[index]);
        if (index == 40) return PlayerSnapshot.cloneOrNull(target.getInventory().getItemInOffHand());
        return PlayerSnapshot.cloneOrNull(target.getInventory().getArmorContents()[index - 36]);
    }

    private static void setReal(Player target, boolean ender, int index, ItemStack item) {
        ItemStack next = PlayerSnapshot.cloneOrNull(item);
        if (ender) {
            target.getEnderChest().setItem(index, next);
        } else if (index < 36) {
            target.getInventory().setItem(index, next);
        } else if (index == 40) {
            target.getInventory().setItemInOffHand(next);
        } else {
            ItemStack[] armor = target.getInventory().getArmorContents();
            armor[index - 36] = next;
            target.getInventory().setArmorContents(armor);
        }
    }

    private static String slotLabel(boolean ender, int index) {
        if (ender) return "Ender " + (index + 1);
        if (index < 9) return "Hotbar " + (index + 1);
        if (index < 36) return "Storage " + index;
        return switch (index) {
            case 36 -> "Boots";
            case 37 -> "Leggings";
            case 38 -> "Chestplate";
            case 39 -> "Helmet";
            default -> "Offhand";
        };
    }

    private static ItemStack icon(Material material, String label, String... lore) {
        ItemStack result = new ItemStack(material);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(label);
            meta.setLore(Arrays.asList(lore));
            result.setItemMeta(meta);
        }
        return result;
    }

    private void refresh(EditorHolder holder, Player target) {
        Inventory ui = holder.getInventory();
        for (int viewSlot = 9; viewSlot < 54; viewSlot++) {
            int real = holder.viewToReal(viewSlot, target);
            ItemStack raw = real < 0 ? null : getReal(target, holder.ender, real);
            holder.lastShown[viewSlot] = PlayerSnapshot.cloneOrNull(raw);
            ItemStack display = raw == null ? null
                    : InspectionMenus.inspectedItem(raw, slotLabel(holder.ender, real), false);
            if (!Objects.equals(ui.getItem(viewSlot), display)) ui.setItem(viewSlot, display);
        }
        ui.setItem(0, icon(Material.ARROW, ChatColor.YELLOW + "Overview"));
        ui.setItem(1, icon(Material.ARROW, ChatColor.GOLD + "Previous page"));
        ui.setItem(2, icon(Material.ARROW, ChatColor.GOLD + "Next page"));
        ui.setItem(3, icon(Material.SLIME_BALL, ChatColor.GREEN + "Refresh"));
        ui.setItem(4, icon(Material.BOOK, ChatColor.AQUA + "LIVE " + (holder.ender ? "ender chest" : "inventory"),
                ChatColor.GRAY + "Editing " + target.getName() + " | Page " + (holder.page + 1),
                ChatColor.GRAY + "Only left/right mouse clicks supported"));
        ui.setItem(5, icon(Material.CHEST, ChatColor.GOLD + "Save manual backup"));
        ui.setItem(8, icon(Material.BARRIER, ChatColor.RED + "Close editor"));
    }

    /** Periodic reconciliation handles player item use and concurrent editors. */
    public void refreshActive() {
        for (EditorHolder holder : sessions.values()) {
            Player admin = Bukkit.getPlayer(holder.adminId);
            Player target = Bukkit.getPlayer(holder.targetId);
            if (admin != null && admin.isOnline() && target != null && target.isOnline()
                    && canEdit(admin) && admin.getOpenInventory().getTopInventory() == holder.inventory)
                refresh(holder, target);
        }
    }

    public void closeFor(UUID targetId) {
        for (EditorHolder holder : sessions.values()) {
            if (!holder.targetId.equals(targetId)) continue;
            Player admin = Bukkit.getPlayer(holder.adminId);
            if (admin != null && admin.isOnline() && admin.getOpenInventory().getTopInventory() == holder.inventory) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (admin.isOnline() && admin.getOpenInventory().getTopInventory() == holder.inventory) {
                        admin.closeInventory();
                        admin.sendMessage(ChatColor.YELLOW + "Remote player disconnected. Editor closed.");
                    }
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EditorHolder holder)) return;
        int raw = event.getRawSlot();
        if (raw >= top.getSize()) {
            // The admin's own inventory remains fully functional, except shift transfers into our fake chest.
            if (event.isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK)
                event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player admin) || !holder.adminId.equals(admin.getUniqueId())) return;
        if (raw < 0) return;
        if (!canEdit(admin)) return;
        Player target = Bukkit.getPlayer(holder.targetId);
        if (target == null || !target.isOnline()) return;
        if (raw < 9) {
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!admin.isOnline() || !canEdit(admin) || admin.getOpenInventory().getTopInventory() != top) return;
                Player liveTarget = Bukkit.getPlayer(holder.targetId);
                if (liveTarget == null || !liveTarget.isOnline()) return;
                if (raw == 0) plugin.menus().openOverview(admin, liveTarget);
                else if (raw == 1 && holder.page > 0) { holder.page--; refresh(holder, liveTarget); }
                else if (raw == 2 && holder.ender && (holder.page + 1) * 45 < liveTarget.getEnderChest().getSize()) {
                    holder.page++; refresh(holder, liveTarget);
                }
                else if (raw == 3) refresh(holder, liveTarget);
                else if (raw == 5) {
                    plugin.createManualBackup(admin, liveTarget);
                } else if (raw == 8) admin.closeInventory();
            });
            return;
        }
        int targetSlot = holder.viewToReal(raw, target);
        if (targetSlot < 0) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;

        // Compare against what the admin saw to avoid overwriting unseen target changes.
        ItemStack displayed = PlayerSnapshot.cloneOrNull(holder.lastShown[raw]);
        ItemStack cursorBefore = PlayerSnapshot.cloneOrNull(event.getCursor());
        boolean right = event.getClick() == ClickType.RIGHT;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!admin.isOnline() || !canEdit(admin) || admin.getOpenInventory().getTopInventory() != top) return;
            Player liveTarget = Bukkit.getPlayer(holder.targetId);
            if (liveTarget == null || !liveTarget.isOnline()) return;
            ItemStack actual = getReal(liveTarget, holder.ender, targetSlot);
            ItemStack currentCursor = PlayerSnapshot.cloneOrNull(admin.getItemOnCursor());
            if (!Objects.equals(displayed, actual) || !Objects.equals(cursorBefore, currentCursor)) {
                refresh(holder, liveTarget);
                admin.sendMessage(ChatColor.YELLOW + "Inventory changed before your click. Refreshed without moving items.");
                return;
            }
            InventoryEditRules.Result result = InventoryEditRules.apply(actual, currentCursor, right);
            if (!result.changed()) return;
            setReal(liveTarget, holder.ender, targetSlot, result.slot());
            admin.setItemOnCursor(result.cursor());
            liveTarget.updateInventory();
            admin.updateInventory();
            plugin.store().saveSnapshot(liveTarget);
            plugin.store().audit("EDIT actor=" + admin.getUniqueId() + " target=" + liveTarget.getUniqueId()
                    + " inventory=" + (holder.ender ? "ender" : "player")
                    + " slot=" + targetSlot + " old=" + describe(actual) + " new=" + describe(result.slot())
                    + " cursor-before=" + describe(currentCursor) + " cursor-after=" + describe(result.cursor()));
            refresh(holder, liveTarget);
        });
    }

    private static String describe(ItemStack stack) {
        return stack == null ? "EMPTY" : stack.getType().name() + "x" + stack.getAmount();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EditorHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder)) return;
        sessions.remove(holder.adminId, holder);
        Player target = Bukkit.getPlayer(holder.targetId);
        if (target != null && target.isOnline()) plugin.store().saveSnapshot(target);
    }
}
