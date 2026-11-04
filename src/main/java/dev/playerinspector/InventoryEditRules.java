package dev.playerinspector;

import org.bukkit.inventory.ItemStack;

/** Pure move semantics; never mutates its arguments. Restricts clicks to left/right. */
public final class InventoryEditRules {
    private InventoryEditRules() {}

    public record Result(ItemStack slot, ItemStack cursor, boolean changed) {}

    public static Result apply(ItemStack current, ItemStack carried, boolean rightClick) {
        ItemStack slot = PlayerSnapshot.cloneOrNull(current);
        ItemStack cursor = PlayerSnapshot.cloneOrNull(carried);
        if (slot == null && cursor == null) return new Result(null, null, false);
        if (!rightClick) {
            if (slot == null && cursor != null && cursor.getAmount() > cursor.getMaxStackSize()) {
                return new Result(slot, cursor, false);
            }
            if (slot != null && cursor != null && slot.isSimilar(cursor)) {
                int cap = Math.max(1, Math.min(slot.getMaxStackSize(), cursor.getMaxStackSize()));
                int transfer = Math.max(0, Math.min(cursor.getAmount(), cap - slot.getAmount()));
                if (transfer == 0) return new Result(slot, cursor, false);
                slot.setAmount(slot.getAmount() + transfer);
                cursor.setAmount(cursor.getAmount() - transfer);
                return new Result(slot, cursor.getAmount() <= 0 ? null : cursor, true);
            }
            return new Result(cursor, slot, true); // swap or pick up/place full stack
        }
        if (cursor == null) {
            if (slot == null) return new Result(null, null, false);
            int take = (slot.getAmount() + 1) / 2;
            cursor = slot.clone();
            cursor.setAmount(take);
            slot.setAmount(slot.getAmount() - take);
            return new Result(slot.getAmount() == 0 ? null : slot, cursor, true);
        }
        if (slot == null) {
            slot = cursor.clone();
            slot.setAmount(1);
            cursor.setAmount(cursor.getAmount() - 1);
            return new Result(slot, cursor.getAmount() == 0 ? null : cursor, true);
        }
        if (!slot.isSimilar(cursor) || slot.getAmount() >= slot.getMaxStackSize()) {
            return new Result(slot, cursor, false);
        }
        slot.setAmount(slot.getAmount() + 1);
        cursor.setAmount(cursor.getAmount() - 1);
        return new Result(slot, cursor.getAmount() == 0 ? null : cursor, true);
    }
}
