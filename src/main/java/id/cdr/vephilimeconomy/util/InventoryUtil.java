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
        if (amount <= 0) {
            return true;
        }
        if (!canFit(inventory, material, amount)) {
            return false;
        }

        ItemStack[] before = cloneContents(inventory.getStorageContents());
        int remaining = amount;
        int max = material.getMaxStackSize();
        while (remaining > 0) {
            int give = Math.min(max, remaining);
            if (!inventory.addItem(new ItemStack(material, give)).isEmpty()) {
                restoreStorage(inventory, before);
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

        ItemStack[] before = cloneContents(inventory.getStorageContents());
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
            if (left <= 0) {
                inventory.setItem(slot, null);
            } else {
                ItemStack updated = stack.clone();
                updated.setAmount(left);
                inventory.setItem(slot, updated);
            }
            remaining -= take;
        }

        if (remaining != 0) {
            restoreStorage(inventory, before);
            return false;
        }
        return true;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static void restoreStorage(PlayerInventory inventory, ItemStack[] snapshot) {
        inventory.setStorageContents(cloneContents(snapshot));
    }
}
