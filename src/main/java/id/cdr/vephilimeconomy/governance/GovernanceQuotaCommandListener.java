package id.cdr.vephilimeconomy.governance;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.pricing.MarketStatisticsService;
import id.cdr.vephilimeconomy.pricing.PricingAdminService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Governance command pre-guard.
 *
 * beta.3 responsibilities: rolling quota/cooldown reservation for direct
 * role-only price/stock mutations.
 *
 * beta.4 RC3 additionally owns /cve pricing because this listener is already
 * wired into the command path. Pricing mutations are scope/hierarchy gated,
 * audited, candidate-validated and applied through safe runtime reload.
 */
public final class GovernanceQuotaCommandListener implements Listener {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String PRICE = "cdrvephilimeconomy.shop.price";
    private static final String STOCK = "cdrvephilimeconomy.shop.stock";
    private static final String PRICING_VIEW = "cdrvephilimeconomy.pricing.view";
    private static final String PRICING_MANAGE = "cdrvephilimeconomy.pricing.manage";

    private final CdrVephilimEconomy plugin;
    private final GovernanceService governance;
    private final GovernanceQuotaLedger quota;
    private final PricingAdminService pricingAdmin;
    private final MarketStatisticsService marketStatistics;

    public GovernanceQuotaCommandListener(CdrVephilimEconomy plugin,
                                          GovernanceService governance,
                                          GovernanceQuotaLedger quota) {
        this.plugin = plugin;
        this.governance = governance;
        this.quota = quota;
        this.pricingAdmin = new PricingAdminService(plugin, plugin.adminAuditService());
        this.marketStatistics = new MarketStatisticsService(plugin.getDataFolder());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] tokens = event.getMessage().trim().split("\\s+");
        if (tokens.length < 2 || !isCveRoot(tokens[0])) return;

        if (tokens[1].equalsIgnoreCase("pricing")) {
            event.setCancelled(true);
            handlePricing(event.getPlayer(), tokens);
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission(ADMIN)) return;
        if (tokens.length < 3 || !tokens[1].equalsIgnoreCase("shop")) return;

        String action = tokens[2].toLowerCase(Locale.ROOT);
        if (action.equals("price")) {
            guardPrice(event, tokens);
        } else if (action.equals("stock")) {
            guardStock(event, tokens);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand() == null ? "" : event.getCommand().trim();
        String[] tokens = command.split("\\s+");
        if (tokens.length < 2 || !isCveRoot(tokens[0]) || !tokens[1].equalsIgnoreCase("pricing")) return;
        event.setCancelled(true);
        handlePricing(event.getSender(), tokens);
    }

    private void handlePricing(CommandSender sender, String[] tokens) {
        if (tokens.length < 3) {
            sendPricingHelp(sender);
            return;
        }

        String action = tokens[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                if (!canViewAny(sender)) return pricingDenied(sender, "view");
                sender.sendMessage("§6[CVE Pricing] §f" + pricingAdmin.status());
            }
            case "show" -> {
                if (tokens.length < 5) {
                    sender.sendMessage("§eUsage: /cve pricing show <shop> <listing>");
                    return;
                }
                String shopId = normalize(tokens[3]);
                if (!canViewShop(sender, shopId)) return pricingDenied(sender, "view " + shopId);
                pricingAdmin.show(shopId, tokens[4]).forEach(sender::sendMessage);
            }
            case "stats" -> handleStats(sender, tokens);
            case "set" -> handlePolicySet(sender, tokens);
            case "global" -> handleGlobal(sender, tokens);
            case "stability" -> handleStability(sender, tokens);
            default -> sendPricingHelp(sender);
        }
    }

    private void handleStats(CommandSender sender, String[] tokens) {
        String shopId = tokens.length >= 4 ? normalize(tokens[3]) : "";
        String listingId = tokens.length >= 5 ? normalize(tokens[4]) : "";
        int hours = 24;
        if (tokens.length >= 6) {
            Integer parsed = parseInt(tokens[5]);
            if (parsed == null || parsed < 1 || parsed > 720) {
                sender.sendMessage("§c[CVE Pricing] hours harus 1-720.");
                return;
            }
            hours = parsed;
        }

        if (shopId.isBlank()) {
            if (!canViewAny(sender)) return pricingDenied(sender, "stats global");
        } else if (!canViewShop(sender, shopId)) {
            return pricingDenied(sender, "stats " + shopId);
        }

        try {
            MarketStatisticsService.Report report = marketStatistics.report(shopId, listingId, hours);
            marketStatistics.render(report).forEach(sender::sendMessage);
        } catch (IOException exception) {
            sender.sendMessage("§c[CVE Market] Gagal membaca audit statistics: " + exception.getMessage());
        }
    }

    private void handlePolicySet(CommandSender sender, String[] tokens) {
        if (tokens.length < 7) {
            sender.sendMessage("§eUsage: /cve pricing set <shop> <listing> <enabled|target|sensitivity|min|max> <value>");
            return;
        }
        String shopId = normalize(tokens[3]);
        String listingId = normalize(tokens[4]);
        String field = tokens[5].toLowerCase(Locale.ROOT);
        String value = tokens[6];

        MutationAccess access = mutationAccess(sender, shopId);
        if (!access.allowed()) return pricingDenied(sender, "mutate " + shopId);

        if (!access.unrestricted() && !managerPolicyGuard(shopId, listingId, field, value)) {
            sender.sendMessage("§c[CVE Pricing] Perubahan melewati guardrail ECONOMY_MANAGER. "
                    + "Gunakan Royal Treasurer atau permission " + PRICING_MANAGE + ".");
            return;
        }

        PricingAdminService.Result result = pricingAdmin.setListing(sender.getName(), shopId, listingId, field, value);
        send(sender, result);
    }

    private void handleGlobal(CommandSender sender, String[] tokens) {
        if (tokens.length < 4) {
            sender.sendMessage("§eUsage: /cve pricing global <on|off>");
            return;
        }
        if (!canManageGlobal(sender)) return pricingDenied(sender, "global mutation");
        Boolean enabled = PricingAdminService.parseBoolean(tokens[3]);
        if (enabled == null) {
            sender.sendMessage("§c[CVE Pricing] Nilai harus on/off atau true/false.");
            return;
        }
        send(sender, pricingAdmin.setGlobal(sender.getName(), enabled));
    }

    private void handleStability(CommandSender sender, String[] tokens) {
        if (tokens.length < 5) {
            sender.sendMessage("§eUsage: /cve pricing stability <quote-cooldown|min-stock-change|reversal-cooldown> <value>");
            return;
        }
        if (!canManageGlobal(sender)) return pricingDenied(sender, "stability mutation");
        Integer value = parseInt(tokens[4]);
        if (value == null) {
            sender.sendMessage("§c[CVE Pricing] Nilai stability harus integer.");
            return;
        }
        send(sender, pricingAdmin.setStability(sender.getName(), tokens[3], value));
    }

    /**
     * Manager guardrails prevent a scoped manager from turning a listing into an
     * aggressive market without Treasurer review. This is deliberately tighter
     * than the absolute parser bounds.
     */
    private boolean managerPolicyGuard(String shopId, String listingId, String rawField, String rawValue) {
        String field = switch (rawField.toLowerCase(Locale.ROOT)) {
            case "enabled", "enable" -> "enabled";
            case "target", "target-stock-ratio" -> "target-stock-ratio";
            case "sensitivity", "sens" -> "sensitivity";
            case "min", "min-multiplier" -> "min-multiplier";
            case "max", "max-multiplier" -> "max-multiplier";
            default -> null;
        };
        if (field == null) return false;
        if (field.equals("enabled")) return PricingAdminService.parseBoolean(rawValue) != null;

        Double target = PricingAdminService.parseFinite(rawValue);
        if (target == null) return false;

        File file = new File(plugin.getDataFolder(), "pricing.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String path = "shops." + shopId + "." + listingId + "." + field;
        double fallback = switch (field) {
            case "target-stock-ratio", "sensitivity" -> 0.50D;
            case "min-multiplier" -> 0.75D;
            case "max-multiplier" -> 1.50D;
            default -> 0.0D;
        };
        double before = yaml.getDouble(path, fallback);
        if (!Double.isFinite(before)) return false;

        return switch (field) {
            case "target-stock-ratio" -> target >= 0.20D && target <= 0.80D
                    && Math.abs(target - before) <= 0.10D + 0.0000001D;
            case "sensitivity" -> target >= 0.0D && target <= 1.50D
                    && Math.abs(target - before) <= 0.25D + 0.0000001D;
            case "min-multiplier" -> target >= 0.50D && target <= 1.0D
                    && Math.abs(target - before) <= 0.25D + 0.0000001D;
            case "max-multiplier" -> target >= 1.0D && target <= 2.0D
                    && Math.abs(target - before) <= 0.25D + 0.0000001D;
            default -> false;
        };
    }

    private MutationAccess mutationAccess(CommandSender sender, String shopId) {
        if (!(sender instanceof Player player)) return MutationAccess.full();
        if (player.hasPermission(ADMIN) || player.hasPermission(PRICING_MANAGE)) return MutationAccess.full();

        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        if (optional.isEmpty()) return MutationAccess.deny();
        GovernanceService.Assignment assignment = optional.get();
        if (!scopeMatches(assignment, shopId)) return MutationAccess.deny();
        if (assignment.role() == GovernanceRole.ROYAL_TREASURER) return MutationAccess.full();
        if (assignment.role() == GovernanceRole.ECONOMY_MANAGER) return MutationAccess.manager();
        return MutationAccess.deny();
    }

    private boolean canManageGlobal(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(PRICING_MANAGE)) return true;
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        return optional.isPresent()
                && optional.get().role() == GovernanceRole.ROYAL_TREASURER
                && optional.get().scopes().contains("*");
    }

    private boolean canViewAny(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(PRICING_VIEW)
                || player.hasPermission(PRICING_MANAGE)) return true;
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        return optional.isPresent() && optional.get().scopes().contains("*");
    }

    private boolean canViewShop(CommandSender sender, String shopId) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(PRICING_VIEW)
                || player.hasPermission(PRICING_MANAGE)) return true;
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        return optional.isPresent() && scopeMatches(optional.get(), shopId);
    }

    private void sendPricingHelp(CommandSender sender) {
        sender.sendMessage("§6[CVE Pricing RC3] §fCommands:");
        sender.sendMessage("§f/cve pricing status");
        sender.sendMessage("§f/cve pricing show <shop> <listing>");
        sender.sendMessage("§f/cve pricing stats [shop] [listing] [hours]");
        sender.sendMessage("§f/cve pricing set <shop> <listing> <enabled|target|sensitivity|min|max> <value>");
        sender.sendMessage("§f/cve pricing global <on|off>");
        sender.sendMessage("§f/cve pricing stability <quote-cooldown|min-stock-change|reversal-cooldown> <value>");
    }

    private void pricingDenied(CommandSender sender, String action) {
        sender.sendMessage("§c[CVE Pricing] Akses ditolak untuk " + action + ".");
    }

    private static void send(CommandSender sender, PricingAdminService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Pricing] " + result.message());
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

    private record MutationAccess(boolean allowed, boolean unrestricted) {
        private static MutationAccess full() { return new MutationAccess(true, true); }
        private static MutationAccess manager() { return new MutationAccess(true, false); }
        private static MutationAccess deny() { return new MutationAccess(false, false); }
    }
}
