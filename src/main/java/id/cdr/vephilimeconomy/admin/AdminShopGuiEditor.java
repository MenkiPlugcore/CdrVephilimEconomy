package id.cdr.vephilimeconomy.admin;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.shop.ListingMode;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * In-game administrative product editor.
 *
 * <p>This GUI intentionally delegates the final mutation to ShopAdminService so
 * schema validation, admin audit, backup, mutation journal, stock reconciliation,
 * and safe runtime reload remain the authoritative mutation path.</p>
 */
public final class AdminShopGuiEditor implements Listener {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String ITEM_PERMISSION = "cdrvephilimeconomy.shop.item";
    private static final int PAGE_SIZE = 45;
    private static final int[] MAX_STOCK_PRESETS = {64, 256, 512, 2048, 4096, 8192};
    private static final int[] INITIAL_STOCK_PRESETS = {0, 64, 128, 256, 512, 1024};

    private final CdrVephilimEconomy plugin;
    private final Map<UUID, WizardState> wizardStates = new HashMap<>();

    public AdminShopGuiEditor(CdrVephilimEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        String[] args = raw.trim().split("\\s+");
        if (args.length < 3) {
            return;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (!(root.equals("cve") || root.equals("veconomy"))) {
            return;
        }
        if (!args[1].equalsIgnoreCase("shop") || !args[2].equalsIgnoreCase("editor")) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!canManage(player)) {
            player.sendMessage(Component.text("[CVE Shop] Kamu tidak memiliki permission " + ITEM_PERMISSION + ".", NamedTextColor.RED));
            return;
        }
        if (args.length < 4) {
            player.sendMessage(Component.text("Usage: /cve shop editor <shop>", NamedTextColor.YELLOW));
            return;
        }
        openEditor(player, args[3], 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (top.getHolder() instanceof EditorHolder holder) {
            event.setCancelled(true);
            if (!canManage(player)) {
                player.closeInventory();
                return;
            }
            if (event.getClickedInventory() != top) {
                return;
            }
            handleEditorClick(player, holder, event.getRawSlot());
            return;
        }

        if (top.getHolder() instanceof WizardHolder holder) {
            event.setCancelled(true);
            if (!canManage(player)) {
                player.closeInventory();
                return;
            }
            if (event.getClickedInventory() != top) {
                return;
            }
            handleWizardClick(player, holder, event.getRawSlot(), event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof EditorHolder || top.getHolder() instanceof WizardHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof WizardHolder) {
            wizardStates.remove(player.getUniqueId());
        }
    }

    private void handleEditorClick(Player player, EditorHolder holder, int rawSlot) {
        Shop shop = plugin.findRuntimeShop(holder.shopId).orElse(null);
        if (shop == null) {
            player.closeInventory();
            player.sendMessage(Component.text("[CVE Shop] Shop sudah tidak tersedia di runtime.", NamedTextColor.RED));
            return;
        }

        if (rawSlot == 45 && holder.page > 0) {
            openEditor(player, shop.id(), holder.page - 1);
            return;
        }
        if (rawSlot == 46) {
            int pages = pageCount(shop);
            if (holder.page + 1 < pages) {
                openEditor(player, shop.id(), holder.page + 1);
            }
            return;
        }
        if (rawSlot == 49) {
            openEditor(player, shop.id(), holder.page);
            return;
        }
        if (rawSlot == 53) {
            startAddWizard(player, shop);
            return;
        }

        String listingId = holder.listingByGuiSlot.get(rawSlot);
        if (listingId != null) {
            ShopListing listing = shop.listings().get(listingId);
            if (listing != null) {
                player.sendMessage(Component.text("[CVE Shop] " + listing.id()
                        + " | " + listing.material().name()
                        + " | slot=" + listing.slot()
                        + " | mode=" + listing.mode()
                        + " | buy=" + listing.buyPrice()
                        + " | sell=" + listing.sellPrice()
                        + " | maxStock=" + listing.maxStock(), NamedTextColor.GOLD));
            }
        }
    }

    private void startAddWizard(Player player, Shop shop) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(Component.text("[CVE Shop] Pegang item produk di main hand sebelum klik Tambah Produk.", NamedTextColor.YELLOW));
            return;
        }

        Material material = hand.getType();
        for (ShopListing listing : shop.listings().values()) {
            if (listing.material() == material) {
                player.sendMessage(Component.text("[CVE Shop] Material " + material.name()
                        + " sudah terdaftar sebagai listing '" + listing.id() + "'.", NamedTextColor.RED));
                return;
            }
        }

        int actualSlot = firstFreeSlot(shop);
        if (actualSlot < 0) {
            player.sendMessage(Component.text("[CVE Shop] Tidak ada slot shop yang kosong.", NamedTextColor.RED));
            return;
        }

        String listingId = nextListingId(shop, material);
        ListingMode mode = defaultMode(shop.id());
        double buyPrice = mode.canBuy() ? 1.0D : 0.0D;
        double sellPrice = mode.canSell() ? 1.0D : 0.0D;
        int initialStock = mode.canBuy() ? 512 : 0;
        int maxStock = 4096;

        WizardState state = new WizardState(shop.id(), listingId, material, actualSlot,
                mode, buyPrice, sellPrice, initialStock, maxStock);
        wizardStates.put(player.getUniqueId(), state);
        openWizard(player, state);
    }

    private void handleWizardClick(Player player, WizardHolder holder, int rawSlot, ClickType click) {
        WizardState state = wizardStates.get(player.getUniqueId());
        if (state == null || !state.shopId.equals(holder.shopId) || !state.listingId.equals(holder.listingId)) {
            player.closeInventory();
            return;
        }

        double step = click.isShiftClick() ? 10.0D : 1.0D;
        switch (rawSlot) {
            case 10 -> {
                state.mode = nextMode(state.mode);
                if (!state.mode.canBuy()) {
                    state.initialStock = 0;
                } else if (state.initialStock == 0) {
                    state.initialStock = Math.min(512, state.maxStock);
                }
                openWizard(player, state);
            }
            case 11 -> {
                state.buyPrice = clampPrice(state.buyPrice - step);
                openWizard(player, state);
            }
            case 13 -> {
                state.buyPrice = clampPrice(state.buyPrice + step);
                openWizard(player, state);
            }
            case 14 -> {
                state.sellPrice = clampPrice(state.sellPrice - step);
                openWizard(player, state);
            }
            case 16 -> {
                state.sellPrice = clampPrice(state.sellPrice + step);
                openWizard(player, state);
            }
            case 18 -> {
                state.initialStock = nextPreset(INITIAL_STOCK_PRESETS, state.initialStock);
                state.initialStock = Math.min(state.initialStock, state.maxStock);
                if (!state.mode.canBuy()) {
                    state.initialStock = 0;
                }
                openWizard(player, state);
            }
            case 19 -> {
                state.maxStock = nextPreset(MAX_STOCK_PRESETS, state.maxStock);
                state.initialStock = Math.min(state.initialStock, state.maxStock);
                openWizard(player, state);
            }
            case 22 -> confirmAdd(player, state);
            case 26 -> {
                wizardStates.remove(player.getUniqueId());
                openEditor(player, state.shopId, 0);
            }
            default -> {
                // informational slot
            }
        }
    }

    private void confirmAdd(Player player, WizardState state) {
        ShopAdminService service = plugin.shopAdminService();
        if (service == null) {
            player.sendMessage(Component.text("[CVE Shop] Shop admin runtime belum siap.", NamedTextColor.RED));
            return;
        }

        double buy = state.mode.canBuy() ? state.buyPrice : 0.0D;
        double sell = state.mode.canSell() ? state.sellPrice : 0.0D;
        int initial = state.mode.canBuy() ? Math.min(state.initialStock, state.maxStock) : 0;

        if (state.mode.canBuy() && buy <= 0.0D) {
            player.sendMessage(Component.text("[CVE Shop] Harga BUY harus lebih dari 0 untuk mode " + state.mode + ".", NamedTextColor.RED));
            return;
        }
        if (state.mode.canSell() && sell <= 0.0D) {
            player.sendMessage(Component.text("[CVE Shop] Harga SELL harus lebih dari 0 untuk mode " + state.mode + ".", NamedTextColor.RED));
            return;
        }

        ShopAdminService.Result result = service.addItem(
                player.getName(), state.shopId, state.listingId, state.material, state.actualSlot,
                state.mode, buy, sell, initial, state.maxStock
        );

        player.sendMessage(Component.text("[CVE Shop] " + result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (!result.success()) {
            return;
        }

        wizardStates.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                openEditor(player, state.shopId, 0);
            }
        });
    }

    public void openEditor(Player player, String rawShopId, int requestedPage) {
        if (!canManage(player)) {
            return;
        }
        String shopId = rawShopId == null ? "" : rawShopId.trim().toLowerCase(Locale.ROOT);
        Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
        if (shop == null) {
            player.sendMessage(Component.text("[CVE Shop] Shop tidak ditemukan: " + shopId, NamedTextColor.RED));
            return;
        }

        int pages = pageCount(shop);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        EditorHolder holder = new EditorHolder(shop.id(), page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("CVE Editor: " + shop.id(), NamedTextColor.DARK_GREEN));
        holder.attach(inventory);

        List<ShopListing> listings = sortedListings(shop);
        int start = page * PAGE_SIZE;
        int end = Math.min(listings.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            int guiSlot = i - start;
            ShopListing listing = listings.get(i);
            inventory.setItem(guiSlot, listingIcon(listing));
            holder.listingByGuiSlot.put(guiSlot, listing.id());
        }

        if (page > 0) {
            inventory.setItem(45, button(Material.ARROW, "Halaman Sebelumnya",
                    "Page " + page + " / " + pages));
        }
        if (page + 1 < pages) {
            inventory.setItem(46, button(Material.ARROW, "Halaman Berikutnya",
                    "Page " + (page + 2) + " / " + pages));
        }
        inventory.setItem(49, button(Material.COMPASS, "Refresh",
                "Muat ulang daftar listing runtime"));
        inventory.setItem(51, button(Material.BOOK, "Cara Tambah Produk",
                "1. Pegang item di main hand",
                "2. Klik Tambah Produk",
                "3. Atur mode, harga, dan stock",
                "4. Klik Konfirmasi"));
        inventory.setItem(53, button(Material.LIME_DYE, "Tambah Produk",
                "Item di main hand akan dijadikan produk baru",
                "Semua perubahan lewat ShopAdminService + audit"));

        player.openInventory(inventory);
    }

    private void openWizard(Player player, WizardState state) {
        WizardHolder holder = new WizardHolder(state.shopId, state.listingId);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("Tambah: " + state.material.name(), NamedTextColor.DARK_GREEN));
        holder.attach(inventory);

        ItemStack preview = new ItemStack(state.material);
        ItemMeta previewMeta = preview.getItemMeta();
        previewMeta.displayName(Component.text(state.listingId, NamedTextColor.GOLD));
        previewMeta.lore(List.of(
                Component.text("Shop: " + state.shopId, NamedTextColor.GRAY),
                Component.text("Target slot: " + state.actualSlot, NamedTextColor.GRAY)
        ));
        preview.setItemMeta(previewMeta);
        inventory.setItem(4, preview);

        inventory.setItem(10, button(Material.COMPARATOR, "Mode: " + state.mode,
                "Klik untuk cycle BUY -> SELL -> BUY_SELL"));
        inventory.setItem(11, button(Material.RED_DYE, "BUY -",
                "Klik: -1", "Shift+klik: -10"));
        inventory.setItem(12, button(Material.GOLD_INGOT, "BUY Price: " + format(state.buyPrice),
                state.mode.canBuy() ? "Aktif" : "Tidak dipakai pada mode ini"));
        inventory.setItem(13, button(Material.LIME_DYE, "BUY +",
                "Klik: +1", "Shift+klik: +10"));

        inventory.setItem(14, button(Material.RED_DYE, "SELL -",
                "Klik: -1", "Shift+klik: -10"));
        inventory.setItem(15, button(Material.EMERALD, "SELL Price: " + format(state.sellPrice),
                state.mode.canSell() ? "Aktif" : "Tidak dipakai pada mode ini"));
        inventory.setItem(16, button(Material.LIME_DYE, "SELL +",
                "Klik: +1", "Shift+klik: +10"));

        inventory.setItem(18, button(Material.CHEST, "Initial Stock: " + state.initialStock,
                state.mode.canBuy() ? "Klik untuk cycle preset" : "SELL-only selalu mulai dari 0"));
        inventory.setItem(19, button(Material.BARREL, "Max Stock: " + state.maxStock,
                "Klik untuk cycle preset"));

        inventory.setItem(22, button(Material.EMERALD_BLOCK, "KONFIRMASI",
                "Simpan listing ke shops.yml secara aman",
                "Admin audit + backup + runtime reload tetap berlaku"));
        inventory.setItem(26, button(Material.BARRIER, "BATAL", "Kembali ke editor shop"));

        player.openInventory(inventory);
    }

    private static ItemStack listingIcon(ShopListing listing) {
        ItemStack stack = new ItemStack(listing.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(listing.id(), NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Material: " + listing.material().name(), NamedTextColor.GRAY),
                Component.text("Shop slot: " + listing.slot(), NamedTextColor.GRAY),
                Component.text("Mode: " + listing.mode(), NamedTextColor.YELLOW),
                Component.text("BUY: " + format(listing.buyPrice()), NamedTextColor.GREEN),
                Component.text("SELL: " + format(listing.sellPrice()), NamedTextColor.AQUA),
                Component.text("Max stock: " + listing.maxStock(), NamedTextColor.GRAY),
                Component.text("Klik untuk melihat detail di chat", NamedTextColor.DARK_GRAY)
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack button(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean canManage(CommandSender sender) {
        return !(sender instanceof Player player)
                || player.isOp()
                || player.hasPermission(ADMIN)
                || player.hasPermission(ITEM_PERMISSION);
    }

    private static ListingMode defaultMode(String shopId) {
        if ("food".equalsIgnoreCase(shopId)) {
            return ListingMode.BUY;
        }
        if ("ore".equalsIgnoreCase(shopId)
                || "farmer".equalsIgnoreCase(shopId)
                || "fisherman".equalsIgnoreCase(shopId)) {
            return ListingMode.SELL;
        }
        return ListingMode.BUY_SELL;
    }

    private static ListingMode nextMode(ListingMode mode) {
        return switch (mode) {
            case BUY -> ListingMode.SELL;
            case SELL -> ListingMode.BUY_SELL;
            case BUY_SELL -> ListingMode.BUY;
        };
    }

    private static int firstFreeSlot(Shop shop) {
        for (int slot = 0; slot < shop.size(); slot++) {
            if (shop.listingBySlot(slot) == null) {
                return slot;
            }
        }
        return -1;
    }

    private static String nextListingId(Shop shop, Material material) {
        String base = material.name().toLowerCase(Locale.ROOT);
        if (!shop.listings().containsKey(base)) {
            return base;
        }
        for (int i = 2; i <= 999; i++) {
            String candidate = base + "_" + i;
            if (!shop.listings().containsKey(candidate)) {
                return candidate;
            }
        }
        return base + "_new";
    }

    private static int pageCount(Shop shop) {
        return Math.max(1, (int) Math.ceil(shop.listings().size() / (double) PAGE_SIZE));
    }

    private static List<ShopListing> sortedListings(Shop shop) {
        List<ShopListing> result = new ArrayList<>(shop.listings().values());
        result.sort(Comparator.comparingInt(ShopListing::slot).thenComparing(ShopListing::id));
        return result;
    }

    private static int nextPreset(int[] values, int current) {
        for (int value : values) {
            if (value > current) {
                return value;
            }
        }
        return values[0];
    }

    private static double clampPrice(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1_000_000_000.0D, Math.round(value * 100.0D) / 100.0D));
    }

    private static String format(double value) {
        if (Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static final class EditorHolder implements InventoryHolder {
        private final String shopId;
        private final int page;
        private final Map<Integer, String> listingByGuiSlot = new HashMap<>();
        private Inventory inventory;

        private EditorHolder(String shopId, int page) {
            this.shopId = shopId;
            this.page = page;
        }

        private void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class WizardHolder implements InventoryHolder {
        private final String shopId;
        private final String listingId;
        private Inventory inventory;

        private WizardHolder(String shopId, String listingId) {
            this.shopId = shopId;
            this.listingId = listingId;
        }

        private void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class WizardState {
        private final String shopId;
        private final String listingId;
        private final Material material;
        private final int actualSlot;
        private ListingMode mode;
        private double buyPrice;
        private double sellPrice;
        private int initialStock;
        private int maxStock;

        private WizardState(String shopId, String listingId, Material material, int actualSlot,
                            ListingMode mode, double buyPrice, double sellPrice,
                            int initialStock, int maxStock) {
            this.shopId = shopId;
            this.listingId = listingId;
            this.material = material;
            this.actualSlot = actualSlot;
            this.mode = mode;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.initialStock = initialStock;
            this.maxStock = maxStock;
        }
    }
}
