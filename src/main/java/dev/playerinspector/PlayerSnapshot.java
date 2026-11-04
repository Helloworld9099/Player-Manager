package dev.playerinspector;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/** Complete, detached equipment/inventory copy. Never exposes mutable source stacks. */
public record PlayerSnapshot(String name, long savedAt, ItemStack[] storage,
                             ItemStack[] armor, ItemStack offhand, ItemStack[] enderChest) {
    public PlayerSnapshot {
        storage = copy(storage, 36);
        armor = copy(armor, 4);
        offhand = cloneOrNull(offhand);
        enderChest = copy(enderChest, enderChest == null || enderChest.length == 0 ? 27 : enderChest.length);
    }

    @Override public ItemStack[] storage() { return copy(storage, storage.length); }
    @Override public ItemStack[] armor() { return copy(armor, armor.length); }
    @Override public ItemStack offhand() { return cloneOrNull(offhand); }
    @Override public ItemStack[] enderChest() { return copy(enderChest, enderChest.length); }

    public static PlayerSnapshot fromPlayer(Player player) {
        return new PlayerSnapshot(player.getName(), System.currentTimeMillis(),
                player.getInventory().getStorageContents(),
                player.getInventory().getArmorContents(),
                player.getInventory().getItemInOffHand(),
                player.getEnderChest().getContents());
    }

    /** Check before restoring across servers with differently-sized ender chests. */
    public boolean fitsEnderChest(int destinationSize) {
        for (int i = Math.max(0, destinationSize); i < enderChest.length; i++) {
            if (cloneOrNull(enderChest[i]) != null) return false;
        }
        return true;
    }

    /** Compare actual item contents, ignoring player name and capture timestamp. */
    public boolean sameItems(PlayerSnapshot other) {
        return other != null && Arrays.equals(storage, other.storage)
                && Arrays.equals(armor, other.armor)
                && java.util.Objects.equals(offhand, other.offhand)
                && Arrays.equals(enderChest, other.enderChest);
    }

    /** Scope is inventory, ender, or all. Call on the server's main thread. */
    public void applyTo(Player target, String scope) {
        if (scope.equals("inventory") || scope.equals("all")) {
            target.getInventory().setStorageContents(storage());
            target.getInventory().setArmorContents(armor());
            target.getInventory().setItemInOffHand(offhand());
        }
        if (scope.equals("ender") || scope.equals("all")) {
            Inventory chest = target.getEnderChest();
            if (!fitsEnderChest(chest.getSize()))
                throw new IllegalStateException("Ender chest has too few slots for this saved inventory");
            chest.setContents(copy(enderChest, chest.getSize()));
        }
        target.updateInventory();
    }

    public static ItemStack cloneOrNull(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack.clone();
    }

    public static ItemStack[] copy(ItemStack[] source, int count) {
        ItemStack[] result = new ItemStack[count];
        if (source != null) {
            for (int i = 0; i < Math.min(count, source.length); i++) result[i] = cloneOrNull(source[i]);
        }
        return result;
    }
}
