package id.cdr.vephilimeconomy.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class InventoryUtil {
    private InventoryUtil() {
    }

    public static boolean canFit(PlayerInventory inventory, Material material, int amount) {
        if (amount <= 0) {
            return true;
        }

        ItemStack template = new ItemStack(material);
        int remaining = amount;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                remaining -= material.getMaxStackSize();
            } else if (stack.isSimilar(template)) {
                remaining -= Math.max(0, stack.getMaxStackSize() - stack.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    public static int countPlain(PlayerInventory inventory, Material material) {
        ItemStack template = new ItemStack(material);
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.isSimilar(template)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    public static boolean addPlain(PlayerInventory inventory, Material material, int amount) {
        if (!canFit(inventory, material, amount)) {
            return false;
        }

        int remaining = amount;
        int max = material.getMaxStackSize();
        while (remaining > 0) {
            int give = Math.min(max, remaining);
            if (!inventory.addItem(new ItemStack(material, give)).isEmpty()) {
                return false;
            }
            remaining -= give;
        }
        return true;
    }

    public static boolean removePlain(PlayerInventory inventory, Material material, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (countPlain(inventory, material) < amount) {
            return false;
        }

        ItemStack template = new ItemStack(material);
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.isSimilar(template)) {
                continue;
            }

            int take = Math.min(stack.getAmount(), remaining);
            int left = stack.getAmount() - take;
            inventory.setItem(slot, left <= 0 ? null : stack.asQuantity(left));
            remaining -= take;
        }
        return remaining == 0;
    }
}
