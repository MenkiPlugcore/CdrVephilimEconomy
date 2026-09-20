package id.cdr.vephilimeconomy.governance;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Optional;

/**
 * Pre-execution guard for role-only direct price/stock mutations.
 *
 * The listener intentionally ignores mutations that are already sensitive under
 * the RC1 per-operation limits, because CveCommand will route those into the RC2
 * approval queue. Rolling quota/cooldown is only reserved for mutations that
 * would otherwise execute directly.
 */
public final class GovernanceQuotaCommandListener implements Listener {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String PRICE = "cdrvephilimeconomy.shop.price";
    private static final String STOCK = "cdrvephilimeconomy.shop.stock";

    private final CdrVephilimEconomy plugin;
    private final GovernanceService governance;
    private final GovernanceQuotaLedger quota;

    public GovernanceQuotaCommandListener(CdrVephilimEconomy plugin,
                                          GovernanceService governance,
                                          GovernanceQuotaLedger quota) {
        this.plugin = plugin;
        this.governance = governance;
        this.quota = quota;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(ADMIN)) return;

        String[] tokens = event.getMessage().trim().split("\\s+");
        if (tokens.length < 3 || !isCveRoot(tokens[0])) return;
        if (!tokens[1].equalsIgnoreCase("shop")) return;

        String action = tokens[2].toLowerCase(Locale.ROOT);
        if (action.equals("price")) {
            guardPrice(event, tokens);
        } else if (action.equals("stock")) {
            guardStock(event, tokens);
        }
    }

    private void guardPrice(PlayerCommandPreprocessEvent event, String[] tokens) {
        Player player = event.getPlayer();
        if (player.hasPermission(PRICE) || tokens.length < 7) return;

        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        if (optional.isEmpty()) return;
        GovernanceService.Assignment assignment = optional.get();
        if (assignment.role() == GovernanceRole.ROYAL_TREASURER
                || !assignment.role().allows(GovernanceCapability.PRICE)) return;

        String shopId = normalize(tokens[3]);
        String listingId = normalize(tokens[4]);
        if (!scopeMatches(assignment, shopId)) return;

        boolean buy;
        if (tokens[5].equalsIgnoreCase("buy")) buy = true;
        else if (tokens[5].equalsIgnoreCase("sell")) buy = false;
        else return;

        Double target = parseDouble(tokens[6]);
        if (target == null || target < 0.0D) return;
        Optional<Shop> shop = plugin.findRuntimeShop(shopId);
        ShopListing listing = shop.map(value -> value.listings().get(listingId)).orElse(null);
        if (listing == null) return;

        double before = buy ? listing.buyPrice() : listing.sellPrice();
        if (Double.compare(before, target) == 0) return;

        // Base 0 or an over-limit per-operation change is routed to approval by CveCommand.
        if (before <= 0.0D) return;
        double percent = Math.abs(target - before) / before * 100.0D;
        double directLimit = assignment.role() == GovernanceRole.ECONOMY_STAFF
                ? plugin.getConfig().getDouble("governance.limits.economy-staff.max-price-change-percent", 20.0D)
                : plugin.getConfig().getDouble("governance.limits.economy-manager.max-price-change-percent", 50.0D);
        directLimit = Double.isFinite(directLimit) ? Math.max(0.0D, directLimit) : 0.0D;
        if (percent > directLimit + 0.0000001D) return;

        GovernanceQuotaLedger.Decision decision = quota.reserve(
                assignment,
                GovernanceQuotaLedger.UsageKind.PRICE_PERCENT,
                percent,
                shopId,
                listingId,
                (buy ? "buy" : "sell") + ": " + before + " -> " + target
        );
        if (!decision.allowed()) {
            event.setCancelled(true);
            player.sendMessage("§c[CVE Governance] " + decision.message());
        }
    }

    private void guardStock(PlayerCommandPreprocessEvent event, String[] tokens) {
        Player player = event.getPlayer();
        if (player.hasPermission(STOCK) || tokens.length < 7) return;

        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        if (optional.isEmpty()) return;
        GovernanceService.Assignment assignment = optional.get();
        if (assignment.role() == GovernanceRole.ROYAL_TREASURER
                || !assignment.role().allows(GovernanceCapability.STOCK_RUNTIME)) return;

        String shopId = normalize(tokens[3]);
        String listingId = normalize(tokens[4]);
        if (!scopeMatches(assignment, shopId)) return;

        ShopAdminService.StockOperation operation;
        try {
            operation = ShopAdminService.StockOperation.valueOf(tokens[5].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return;
        }
        Integer amount = parseInt(tokens[6]);
        if (amount == null || amount < 0 || amount == 0) return;

        // SET or an over-limit delta is routed to approval by CveCommand.
        if (operation == ShopAdminService.StockOperation.SET) return;
        long directLimit = assignment.role() == GovernanceRole.ECONOMY_STAFF
                ? plugin.getConfig().getLong("governance.limits.economy-staff.max-runtime-stock-delta", 128L)
                : plugin.getConfig().getLong("governance.limits.economy-manager.max-runtime-stock-delta", 1024L);
        directLimit = Math.max(0L, directLimit);
        if (amount > directLimit) return;

        Optional<Shop> shop = plugin.findRuntimeShop(shopId);
        ShopListing listing = shop.map(value -> value.listings().get(listingId)).orElse(null);
        if (listing == null) return;

        GovernanceQuotaLedger.Decision decision = quota.reserve(
                assignment,
                GovernanceQuotaLedger.UsageKind.STOCK_DELTA,
                amount.doubleValue(),
                shopId,
                listingId,
                operation.name() + " " + amount
        );
        if (!decision.allowed()) {
            event.setCancelled(true);
            player.sendMessage("§c[CVE Governance] " + decision.message());
        }
    }

    private static boolean isCveRoot(String raw) {
        String root = raw.startsWith("/") ? raw.substring(1) : raw;
        root = root.toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        if (namespace >= 0 && namespace + 1 < root.length()) {
            root = root.substring(namespace + 1);
        }
        return root.equals("cve") || root.equals("veconomy");
    }

    private static boolean scopeMatches(GovernanceService.Assignment assignment, String shopId) {
        return assignment.scopes().contains("*") || assignment.scopes().contains(shopId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
