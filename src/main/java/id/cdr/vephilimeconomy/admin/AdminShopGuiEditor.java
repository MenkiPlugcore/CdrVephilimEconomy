package id.cdr.vephilimeconomy.admin;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.shop.ListingMode;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-game administrative shop/category editor.
 *
 * <p>All durable mutations delegate to ShopAdminService so schema validation,
 * admin audit, backups, the mutation journal, stock reconciliation and safe
 * runtime reload remain authoritative. The GUI itself never edits shops.yml.</p>
 */
public final class AdminShopGuiEditor implements Listener {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String VIEW_PERMISSION = "cdrvephilimeconomy.shop.view";
    private static final String CREATE_PERMISSION = "cdrvephilimeconomy.shop.create";
    private static final String BIND_PERMISSION = "cdrvephilimeconomy.shop.bind";
    private static final String ITEM_PERMISSION = "cdrvephilimeconomy.shop.item";
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final int PAGE_SIZE = 45;
    private static final int[] MAX_STOCK_PRESETS = {64, 256, 512, 2048, 4096, 8192};
    private static final int[] INITIAL_STOCK_PRESETS = {0, 64, 128, 256, 512, 1024};

    private final CdrVephilimEconomy plugin;
    private final Map<UUID, WizardState> wizardStates = new ConcurrentHashMap<>();
    private final Map<UUID, CategoryCreateState> categoryStates = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingNpcBind = new ConcurrentHashMap<>();

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
        if (!canOpen(player)) {
            player.sendMessage(Component.text("[CVE Shop] Kamu tidak memiliki permission untuk membuka Shop Editor.", NamedTextColor.RED));
            return;
        }
        if (args.length < 4) {
            openDashboard(player, 0);
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

        if (top.getHolder() instanceof DashboardHolder holder) {
            event.setCancelled(true);
            if (!canOpen(player) || event.getClickedInventory() != top) {
                return;
            }
            handleDashboardClick(player, holder, event.getRawSlot());
            return;
        }

        if (top.getHolder() instanceof EditorHolder holder) {
            event.setCancelled(true);
            if (!canOpen(player)) {
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
            if (!canManageItems(player)) {
                player.closeInventory();
                return;
            }
            if (event.getClickedInventory() != top) {
                return;
            }
            handleWizardClick(player, holder, event.getRawSlot(), event.getClick());
            return;
        }

        if (top.getHolder() instanceof CategorySizeHolder holder) {
            event.setCancelled(true);
            if (!canCreate(player) || event.getClickedInventory() != top) {
                return;
            }
            handleCategorySizeClick(player, holder, event.getRawSlot());
            return;
        }

        if (top.getHolder() instanceof BindingHolder holder) {
            event.setCancelled(true);
            if (!canBind(player) || event.getClickedInventory() != top) {
                return;
            }
            handleBindingClick(player, holder, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof DashboardHolder
                || top.getHolder() instanceof EditorHolder
                || top.getHolder() instanceof WizardHolder
                || top.getHolder() instanceof CategorySizeHolder
                || top.getHolder() instanceof BindingHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof WizardHolder)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                wizardStates.remove(playerId);
                return;
            }
            Inventory current = player.getOpenInventory().getTopInventory();
            if (!(current.getHolder() instanceof WizardHolder)) {
                wizardStates.remove(playerId);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!categoryStates.containsKey(playerId)) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> handleCategoryChat(event.getPlayer(), message));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        String shopId = pendingNpcBind.get(player.getUniqueId());
        if (shopId == null) {
            return;
        }

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getRightClicked());
        if (npc == null) {
            player.sendMessage(Component.text("[CVE Shop] Itu bukan Citizens NPC. Klik kanan Citizens NPC yang ingin dibind, atau ketik /cve shop editor untuk batal.", NamedTextColor.YELLOW));
            return;
        }

        event.setCancelled(true);
        Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
        if (shop == null) {
            pendingNpcBind.remove(player.getUniqueId());
            player.sendMessage(Component.text("[CVE Shop] Shop sudah tidak tersedia.", NamedTextColor.RED));
            return;
        }

        for (String otherId : plugin.runtimeShopIds()) {
            if (otherId.equalsIgnoreCase(shopId)) {
                continue;
            }
            Shop other = plugin.findRuntimeShop(otherId).orElse(null);
            if (other != null && other.npcId() == npc.getId()) {
                player.sendMessage(Component.text("[CVE Shop] NPC #" + npc.getId()
                        + " sudah dibind ke shop '" + other.id() + "'. Unbind/rebind shop tersebut terlebih dahulu.", NamedTextColor.RED));
                return;
            }
        }

        ShopAdminService service = plugin.shopAdminService();
        if (service == null) {
            pendingNpcBind.remove(player.getUniqueId());
            player.sendMessage(Component.text("[CVE Shop] Shop admin runtime belum siap.", NamedTextColor.RED));
            return;
        }

        ShopAdminService.Result result = service.bindNpc(player.getName(), shop.id(), npc.getId());
        player.sendMessage(Component.text("[CVE Shop] " + result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (result.success()) {
            pendingNpcBind.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    openEditor(player, shop.id(), 0);
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        wizardStates.remove(id);
        categoryStates.remove(id);
        pendingNpcBind.remove(id);
    }

    private void handleDashboardClick(Player player, DashboardHolder holder, int rawSlot) {
        if (rawSlot == 45 && holder.page > 0) {
            openDashboard(player, holder.page - 1);
            return;
        }
        if (rawSlot == 46 && holder.page + 1 < dashboardPageCount()) {
            openDashboard(player, holder.page + 1);
            return;
        }
        if (rawSlot == 49) {
            openDashboard(player, holder.page);
            return;
        }
        if (rawSlot == 53) {
            if (!canCreate(player)) {
                player.sendMessage(Component.text("[CVE Shop] Kamu tidak memiliki permission " + CREATE_PERMISSION + ".", NamedTextColor.RED));
                return;
            }
            beginCategoryCreate(player);
            return;
        }

        String shopId = holder.shopByGuiSlot.get(rawSlot);
        if (shopId != null) {
            openEditor(player, shopId, 0);
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
        if (rawSlot == 47) {
            openDashboard(player, 0);
            return;
        }
        if (rawSlot == 48) {
            if (!canBind(player)) {
                player.sendMessage(Component.text("[CVE Shop] Kamu tidak memiliki permission " + BIND_PERMISSION + ".", NamedTextColor.RED));
                return;
            }
            if (shop.npcId() < 0) {
                beginNpcBind(player, shop.id());
            } else {
                openBindingMenu(player, shop);
            }
            return;
        }
        if (rawSlot == 49) {
            openEditor(player, shop.id(), holder.page);
            return;
        }
        if (rawSlot == 53) {
            if (!canManageItems(player)) {
                player.sendMessage(Component.text("[CVE Shop] Kamu tidak memiliki permission " + ITEM_PERMISSION + ".", NamedTextColor.RED));
                return;
            }
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

    private void beginCategoryCreate(Player player) {
        wizardStates.remove(player.getUniqueId());
        pendingNpcBind.remove(player.getUniqueId());
        categoryStates.put(player.getUniqueId(), new CategoryCreateState());
        player.closeInventory();
        player.sendMessage(Component.text("[CVE Shop] Ketik ID kategori/shop baru di chat. Contoh: alchemist", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Ketik 'batal' untuk membatalkan.", NamedTextColor.GRAY));
    }

    private void handleCategoryChat(Player player, String rawMessage) {
        CategoryCreateState state = categoryStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.equalsIgnoreCase("batal") || message.equalsIgnoreCase("cancel")) {
            categoryStates.remove(player.getUniqueId());
            player.sendMessage(Component.text("[CVE Shop] Pembuatan kategori dibatalkan.", NamedTextColor.YELLOW));
            openDashboard(player, 0);
            return;
        }

        if (state.stage == CategoryStage.ID) {
            String id = message.toLowerCase(Locale.ROOT);
            if (!SAFE_ID.matcher(id).matches()) {
                player.sendMessage(Component.text("[CVE Shop] ID harus [a-z0-9_-], maksimal 48 karakter. Coba lagi.", NamedTextColor.RED));
                return;
            }
            if (plugin.findRuntimeShop(id).isPresent()) {
                player.sendMessage(Component.text("[CVE Shop] Shop '" + id + "' sudah ada. Gunakan ID lain.", NamedTextColor.RED));
                return;
            }
            state.shopId = id;
            state.stage = CategoryStage.DISPLAY_NAME;
            player.sendMessage(Component.text("[CVE Shop] ID: " + id, NamedTextColor.GREEN));
            player.sendMessage(Component.text("Sekarang ketik nama tampilan kategori. Contoh: Alchemist Kerajaan", NamedTextColor.GOLD));
            return;
        }

        if (state.stage == CategoryStage.DISPLAY_NAME) {
            if (message.isBlank() || message.length() > 128) {
                player.sendMessage(Component.text("[CVE Shop] Nama tampilan harus 1-128 karakter. Coba lagi.", NamedTextColor.RED));
                return;
            }
            state.displayName = message;
            categoryStates.remove(player.getUniqueId());
            openCategorySize(player, state.shopId, state.displayName);
        }
    }

    private void openCategorySize(Player player, String shopId, String displayName) {
        CategorySizeHolder holder = new CategorySizeHolder(shopId, displayName);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("Ukuran Shop: " + shopId, NamedTextColor.DARK_GREEN));
        holder.attach(inventory);

        inventory.setItem(4, button(Material.CHEST, "Kategori Baru: " + shopId,
                "Nama: " + displayName,
                "Pilih jumlah slot shop di bawah"));
        inventory.setItem(10, sizeButton(9));
        inventory.setItem(11, sizeButton(18));
        inventory.setItem(12, sizeButton(27));
        inventory.setItem(14, sizeButton(36));
        inventory.setItem(15, sizeButton(45));
        inventory.setItem(16, sizeButton(54));
        inventory.setItem(22, button(Material.BARRIER, "BATAL", "Kembali ke daftar kategori"));
        player.openInventory(inventory);
    }

    private void handleCategorySizeClick(Player player, CategorySizeHolder holder, int rawSlot) {
        if (rawSlot == 22) {
            openDashboard(player, 0);
            return;
        }
        int size = switch (rawSlot) {
            case 10 -> 9;
            case 11 -> 18;
            case 12 -> 27;
            case 14 -> 36;
            case 15 -> 45;
            case 16 -> 54;
            default -> -1;
        };
        if (size < 0) {
            return;
        }

        ShopAdminService service = plugin.shopAdminService();
        if (service == null) {
            player.sendMessage(Component.text("[CVE Shop] Shop admin runtime belum siap.", NamedTextColor.RED));
            return;
        }
        ShopAdminService.Result result = service.createShop(player.getName(), holder.shopId, holder.displayName, size);
        player.sendMessage(Component.text("[CVE Shop] " + result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (result.success()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    openEditor(player, holder.shopId, 0);
                }
            });
        }
    }

    private static ItemStack sizeButton(int size) {
        return button(Material.CHEST, size + " Slot", (size / 9) + " baris inventory");
    }

    private void openBindingMenu(Player player, Shop shop) {
        BindingHolder holder = new BindingHolder(shop.id());
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("NPC Binding: " + shop.id(), NamedTextColor.DARK_GREEN));
        holder.attach(inventory);

        inventory.setItem(4, button(Material.NAME_TAG, "NPC Binding",
                "Shop: " + shop.id(),
                shop.npcId() < 0 ? "Status: UNBOUND" : "Status: BOUND ke NPC #" + shop.npcId()));
        inventory.setItem(11, button(Material.LIME_DYE,
                shop.npcId() < 0 ? "Bind Citizens NPC" : "Rebind Citizens NPC",
                "Tutup GUI lalu klik kanan Citizens NPC"));
        if (shop.npcId() >= 0) {
            inventory.setItem(15, button(Material.RED_DYE, "Unbind NPC",
                    "Lepaskan NPC #" + shop.npcId() + " dari shop ini"));
        }
        inventory.setItem(22, button(Material.BARRIER, "KEMBALI", "Kembali ke editor shop"));
        player.openInventory(inventory);
    }

    private void handleBindingClick(Player player, BindingHolder holder, int rawSlot) {
        Shop shop = plugin.findRuntimeShop(holder.shopId).orElse(null);
        if (shop == null) {
            player.closeInventory();
            player.sendMessage(Component.text("[CVE Shop] Shop sudah tidak tersedia.", NamedTextColor.RED));
            return;
        }
        if (rawSlot == 11) {
            beginNpcBind(player, shop.id());
            return;
        }
        if (rawSlot == 15 && shop.npcId() >= 0) {
            ShopAdminService service = plugin.shopAdminService();
            if (service == null) {
                player.sendMessage(Component.text("[CVE Shop] Shop admin runtime belum siap.", NamedTextColor.RED));
                return;
            }
            ShopAdminService.Result result = service.bindNpc(player.getName(), shop.id(), -1);
            player.sendMessage(Component.text("[CVE Shop] " + result.message(),
                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (result.success()) {
                pendingNpcBind.remove(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        openEditor(player, shop.id(), 0);
                    }
                });
            }
            return;
        }
        if (rawSlot == 22) {
            openEditor(player, shop.id(), 0);
        }
    }

    private void beginNpcBind(Player player, String shopId) {
        categoryStates.remove(player.getUniqueId());
        wizardStates.remove(player.getUniqueId());
        pendingNpcBind.put(player.getUniqueId(), shopId);
        player.closeInventory();
        player.sendMessage(Component.text("[CVE Shop] Mode binding aktif untuk shop '" + shopId + "'.", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Klik kanan Citizens NPC yang ingin dipakai. Buka /cve shop editor untuk membatalkan.", NamedTextColor.YELLOW));
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

    public void openDashboard(Player player, int requestedPage) {
        if (!canOpen(player)) {
            return;
        }
        pendingNpcBind.remove(player.getUniqueId());
        categoryStates.remove(player.getUniqueId());

        List<String> ids = sortedShopIds();
        int pages = Math.max(1, (int) Math.ceil(ids.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        DashboardHolder holder = new DashboardHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("CVE Shop Categories", NamedTextColor.DARK_GREEN));
        holder.attach(inventory);

        int start = page * PAGE_SIZE;
        int end = Math.min(ids.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            String shopId = ids.get(i);
            Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
            if (shop == null) {
                continue;
            }
            int guiSlot = i - start;
            inventory.setItem(guiSlot, categoryIcon(shop));
            holder.shopByGuiSlot.put(guiSlot, shop.id());
        }

        if (page > 0) {
            inventory.setItem(45, button(Material.ARROW, "Halaman Sebelumnya", "Page " + page + " / " + pages));
        }
        if (page + 1 < pages) {
            inventory.setItem(46, button(Material.ARROW, "Halaman Berikutnya", "Page " + (page + 2) + " / " + pages));
        }
        inventory.setItem(49, button(Material.COMPASS, "Refresh", "Muat ulang kategori dari runtime"));
        inventory.setItem(51, button(Material.BOOK, "Shop Category Manager",
                "Klik kategori untuk membuka editor",
                "Kategori baru dibuat aman lewat ShopAdminService"));
        inventory.setItem(53, canCreate(player)
                ? button(Material.LIME_DYE, "Buat Kategori Shop Baru",
                    "Wizard chat: ID -> nama -> ukuran GUI",
                    "Default: disabled dan belum dibind NPC")
                : button(Material.GRAY_DYE, "Buat Kategori Shop Baru", "Butuh permission " + CREATE_PERMISSION));

        player.openInventory(inventory);
    }

    public void openEditor(Player player, String rawShopId, int requestedPage) {
        if (!canOpen(player)) {
            return;
        }
        pendingNpcBind.remove(player.getUniqueId());
        categoryStates.remove(player.getUniqueId());

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
            inventory.setItem(45, button(Material.ARROW, "Halaman Sebelumnya", "Page " + page + " / " + pages));
        }
        if (page + 1 < pages) {
            inventory.setItem(46, button(Material.ARROW, "Halaman Berikutnya", "Page " + (page + 2) + " / " + pages));
        }
        inventory.setItem(47, button(Material.CHEST, "Kategori Shop", "Kembali ke daftar kategori"));
        inventory.setItem(48, canBind(player)
                ? button(Material.NAME_TAG, shop.npcId() < 0 ? "Bind Citizens NPC" : "NPC #" + shop.npcId() + " (Bound)",
                    shop.npcId() < 0 ? "Klik lalu pilih Citizens NPC di dunia" : "Klik untuk Rebind / Unbind")
                : button(Material.GRAY_DYE, "NPC Binding", "Butuh permission " + BIND_PERMISSION));
        inventory.setItem(49, button(Material.COMPASS, "Refresh", "Muat ulang daftar listing runtime"));
        inventory.setItem(51, button(Material.BOOK, "Shop Info",
                "ID: " + shop.id(),
                "Size: " + shop.size(),
                "Listings: " + shop.listings().size(),
                "Enabled: " + shop.enabled(),
                "NPC: " + (shop.npcId() < 0 ? "UNBOUND" : "#" + shop.npcId())));
        inventory.setItem(53, canManageItems(player)
                ? button(Material.LIME_DYE, "Tambah Produk",
                    "Item di main hand akan dijadikan produk baru",
                    "Semua perubahan lewat ShopAdminService + audit")
                : button(Material.GRAY_DYE, "Tambah Produk", "Butuh permission " + ITEM_PERMISSION));

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
        inventory.setItem(11, button(Material.RED_DYE, "BUY -", "Klik: -1", "Shift+klik: -10"));
        inventory.setItem(12, button(Material.GOLD_INGOT, "BUY Price: " + format(state.buyPrice),
                state.mode.canBuy() ? "Aktif" : "Tidak dipakai pada mode ini"));
        inventory.setItem(13, button(Material.LIME_DYE, "BUY +", "Klik: +1", "Shift+klik: +10"));

        inventory.setItem(14, button(Material.RED_DYE, "SELL -", "Klik: -1", "Shift+klik: -10"));
        inventory.setItem(15, button(Material.EMERALD, "SELL Price: " + format(state.sellPrice),
                state.mode.canSell() ? "Aktif" : "Tidak dipakai pada mode ini"));
        inventory.setItem(16, button(Material.LIME_DYE, "SELL +", "Klik: +1", "Shift+klik: +10"));

        inventory.setItem(18, button(Material.CHEST, "Initial Stock: " + state.initialStock,
                state.mode.canBuy() ? "Klik untuk cycle preset" : "SELL-only selalu mulai dari 0"));
        inventory.setItem(19, button(Material.BARREL, "Max Stock: " + state.maxStock, "Klik untuk cycle preset"));

        inventory.setItem(22, button(Material.EMERALD_BLOCK, "KONFIRMASI",
                "Simpan listing ke shops.yml secara aman",
                "Admin audit + backup + runtime reload tetap berlaku"));
        inventory.setItem(26, button(Material.BARRIER, "BATAL", "Kembali ke editor shop"));

        player.openInventory(inventory);
    }

    private static ItemStack categoryIcon(Shop shop) {
        Material icon = shop.npcId() >= 0 ? Material.EMERALD : Material.CHEST;
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(shop.id(), NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Size: " + shop.size(), NamedTextColor.GRAY),
                Component.text("Produk: " + shop.listings().size(), NamedTextColor.GRAY),
                Component.text("Enabled: " + shop.enabled(), shop.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED),
                Component.text("NPC: " + (shop.npcId() < 0 ? "UNBOUND" : "#" + shop.npcId()), NamedTextColor.YELLOW),
                Component.text("Klik untuk buka Shop Editor", NamedTextColor.DARK_GRAY)
        ));
        stack.setItemMeta(meta);
        return stack;
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

    private boolean canOpen(CommandSender sender) {
        return !(sender instanceof Player player)
                || player.isOp()
                || player.hasPermission(ADMIN)
                || player.hasPermission(VIEW_PERMISSION)
                || player.hasPermission(ITEM_PERMISSION)
                || player.hasPermission(CREATE_PERMISSION)
                || player.hasPermission(BIND_PERMISSION);
    }

    private boolean canManageItems(CommandSender sender) {
        return !(sender instanceof Player player)
                || player.isOp()
                || player.hasPermission(ADMIN)
                || player.hasPermission(ITEM_PERMISSION);
    }

    private boolean canCreate(CommandSender sender) {
        return !(sender instanceof Player player)
                || player.isOp()
                || player.hasPermission(ADMIN)
                || player.hasPermission(CREATE_PERMISSION);
    }

    private boolean canBind(CommandSender sender) {
        return !(sender instanceof Player player)
                || player.isOp()
                || player.hasPermission(ADMIN)
                || player.hasPermission(BIND_PERMISSION);
    }

    private List<String> sortedShopIds() {
        List<String> ids = new ArrayList<>(plugin.runtimeShopIds());
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }

    private int dashboardPageCount() {
        return Math.max(1, (int) Math.ceil(plugin.runtimeShopIds().size() / (double) PAGE_SIZE));
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

    private static final class DashboardHolder implements InventoryHolder {
        private final int page;
        private final Map<Integer, String> shopByGuiSlot = new ConcurrentHashMap<>();
        private Inventory inventory;

        private DashboardHolder(int page) {
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

    private static final class EditorHolder implements InventoryHolder {
        private final String shopId;
        private final int page;
        private final Map<Integer, String> listingByGuiSlot = new ConcurrentHashMap<>();
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

    private static final class CategorySizeHolder implements InventoryHolder {
        private final String shopId;
        private final String displayName;
        private Inventory inventory;

        private CategorySizeHolder(String shopId, String displayName) {
            this.shopId = shopId;
            this.displayName = displayName;
        }

        private void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BindingHolder implements InventoryHolder {
        private final String shopId;
        private Inventory inventory;

        private BindingHolder(String shopId) {
            this.shopId = shopId;
        }

        private void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private enum CategoryStage {
        ID,
        DISPLAY_NAME
    }

    private static final class CategoryCreateState {
        private CategoryStage stage = CategoryStage.ID;
        private String shopId;
        private String displayName;
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
