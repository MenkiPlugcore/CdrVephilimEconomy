package id.cdr.vephilimeconomy.governance;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.market.MarketEventService;
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
 * beta.4 adds governed /cve pricing commands. beta.5 RC1 adds governed
 * temporary RP market events without exposing full admin access to economy staff.
 */
public final class GovernanceQuotaCommandListener implements Listener {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String PRICE = "cdrvephilimeconomy.shop.price";
    private static final String STOCK = "cdrvephilimeconomy.shop.stock";
    private static final String PRICING_VIEW = "cdrvephilimeconomy.pricing.view";
    private static final String PRICING_MANAGE = "cdrvephilimeconomy.pricing.manage";
    private static final String MARKET_VIEW = "cdrvephilimeconomy.market.view";
    private static final String MARKET_MANAGE = "cdrvephilimeconomy.market.manage";

    private final CdrVephilimEconomy plugin;
    private final GovernanceService governance;
    private final GovernanceQuotaLedger quota;
    private final PricingAdminService pricingAdmin;
    private final MarketStatisticsService marketStatistics;
    private final MarketEventService marketEvents;

    public GovernanceQuotaCommandListener(CdrVephilimEconomy plugin,
                                          GovernanceService governance,
                                          GovernanceQuotaLedger quota) {
        this.plugin = plugin;
        this.governance = governance;
        this.quota = quota;
        this.pricingAdmin = new PricingAdminService(plugin, plugin.adminAuditService());
        this.marketStatistics = new MarketStatisticsService(plugin.getDataFolder());
        this.marketEvents = new MarketEventService(plugin, plugin.adminAuditService());
        try {
            this.marketEvents.load();
        } catch (IOException exception) {
            plugin.getLogger().severe("Beta.5 market-event runtime tidak sehat: " + exception.getMessage());
        }
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
        if (tokens[1].equalsIgnoreCase("market")) {
            event.setCancelled(true);
            handleMarket(event.getPlayer(), tokens);
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
        if (tokens.length < 2 || !isCveRoot(tokens[0])) return;
        if (tokens[1].equalsIgnoreCase("pricing")) {
            event.setCancelled(true);
            handlePricing(event.getSender(), tokens);
        } else if (tokens[1].equalsIgnoreCase("market")) {
            event.setCancelled(true);
            handleMarket(event.getSender(), tokens);
        }
    }

    private void handleMarket(CommandSender sender, String[] tokens) {
        try {
            marketEvents.load();
        } catch (IOException exception) {
            sender.sendMessage("§c[CVE Market] market-events.yml tidak valid: " + exception.getMessage());
            return;
        }

        if (tokens.length < 3) {
            sendMarketHelp(sender);
            return;
        }
        String action = tokens[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                if (!canViewMarketAny(sender)) {
                    returnMarketDenied(sender, "status");
                    return;
                }
                String runtime = plugin.dynamicPricingService() == null
                        ? "UNAVAILABLE"
                        : plugin.dynamicPricingService().marketEventStatus();
                sender.sendMessage("§6[CVE Market] §fruntime=" + runtime + " §7admin=" + marketEvents.statusSummary());
            }
            case "list" -> {
                if (!canViewMarketAny(sender)) {
                    returnMarketDenied(sender, "list");
                    return;
                }
                boolean any = false;
                sender.sendMessage("§6[CVE Market] §fEvents:");
                for (MarketEventService.MarketEvent marketEvent : marketEvents.all()) {
                    if (!canViewMarketScope(sender, marketEvent.shopId())) continue;
                    any = true;
                    String state = marketEvent.activeAt(java.time.Instant.now()) ? "§aACTIVE" : "§7INACTIVE";
                    sender.sendMessage("§f- " + marketEvent.id() + " §7| " + state
                            + " §7| " + marketEvent.type()
                            + " §7| scope=§f" + marketEvent.shopId() + "/" + marketEvent.listingId()
                            + " §7| buy=x§f" + marketEvent.buyMultiplier()
                            + " §7sell=x§f" + marketEvent.sellMultiplier()
                            + " §7| until=§f" + marketEvent.endsAt());
                }
                if (!any) sender.sendMessage("§7Tidak ada event yang dapat kamu lihat pada scope governance saat ini.");
            }
            case "show" -> {
                if (tokens.length < 4) {
                    sender.sendMessage("§eUsage: /cve market show <id>");
                    return;
                }
                MarketEventService.MarketEvent marketEvent = marketEvents.find(tokens[3]).orElse(null);
                if (marketEvent == null) {
                    sender.sendMessage("§c[CVE Market] Event tidak ditemukan: " + tokens[3]);
                    return;
                }
                if (!canViewMarketScope(sender, marketEvent.shopId())) {
                    returnMarketDenied(sender, "show " + marketEvent.id());
                    return;
                }
                marketEvents.showLines(tokens[3]).forEach(sender::sendMessage);
            }
            case "scarcity" -> createMarketPreset(sender, tokens, MarketEventService.EventType.SCARCITY);
            case "buybonus", "kingdombuy" -> createMarketPreset(sender, tokens, MarketEventService.EventType.KINGDOM_BUY_BONUS);
            case "discount" -> createMarketPreset(sender, tokens, MarketEventService.EventType.DISCOUNT);
            case "end" -> {
                if (tokens.length < 4) {
                    sender.sendMessage("§eUsage: /cve market end <id> [reason]");
                    return;
                }
                MarketEventService.MarketEvent marketEvent = marketEvents.find(tokens[3]).orElse(null);
                if (marketEvent == null) {
                    sender.sendMessage("§c[CVE Market] Event tidak ditemukan: " + tokens[3]);
                    return;
                }
                if (!canManageMarketScope(sender, marketEvent.shopId())) {
                    returnMarketDenied(sender, "end " + marketEvent.id());
                    return;
                }
                sendMarket(sender, marketEvents.end(sender.getName(), tokens[3], joinTail(tokens, 4)));
            }
            default -> sendMarketHelp(sender);
        }
    }

    private void createMarketPreset(CommandSender sender, String[] tokens, MarketEventService.EventType type) {
        if (tokens.length < 8) {
            sender.sendMessage("§eUsage: /cve market " + marketAction(type)
                    + " <id> <shop|*> <listing|*> <multiplier> <minutes> [announcement]");
            return;
        }
        String shopId = normalize(tokens[4]);
        String listingId = normalize(tokens[5]);
        if (!canManageMarketScope(sender, shopId)) {
            returnMarketDenied(sender, "create " + shopId);
            return;
        }

        if (!shopId.equals("*")) {
            Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
            if (shop == null) {
                sender.sendMessage("§c[CVE Market] Shop runtime tidak ditemukan: " + shopId);
                return;
            }
            if (!listingId.equals("*") && !shop.listings().containsKey(listingId)) {
                sender.sendMessage("§c[CVE Market] Listing runtime tidak ditemukan: " + shopId + "/" + listingId);
                return;
            }
        } else if (!listingId.equals("*")) {
            boolean listingExists = plugin.runtimeShopIds().stream()
                    .anyMatch(id -> plugin.runtimeListingIds(id).contains(listingId));
            if (!listingExists) {
                sender.sendMessage("§c[CVE Market] Listing ID tidak ditemukan pada runtime shop mana pun: " + listingId);
                return;
            }
        }

        Double multiplier = parseDouble(tokens[6]);
        Integer minutes = parseInt(tokens[7]);
        if (multiplier == null || minutes == null) {
            sender.sendMessage("§c[CVE Market] multiplier harus angka dan minutes harus integer.");
            return;
        }
        MarketEventService.Result result = marketEvents.createPreset(
                sender.getName(), tokens[3], type, shopId, listingId,
                multiplier, minutes, joinTail(tokens, 8));
        sendMarket(sender, result);
    }

    private static String marketAction(MarketEventService.EventType type) {
        return switch (type) {
            case SCARCITY -> "scarcity";
            case KINGDOM_BUY_BONUS -> "buybonus";
            case DISCOUNT -> "discount";
        };
    }

    private boolean canViewMarketAny(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(MARKET_VIEW)
                || player.hasPermission(MARKET_MANAGE)) return true;
        return governance.assignmentFor(player.getUniqueId()).isPresent();
    }

    private boolean canViewMarketScope(CommandSender sender, String shopId) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(MARKET_VIEW)
                || player.hasPermission(MARKET_MANAGE)) return true;
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        if (optional.isEmpty()) return false;
        GovernanceService.Assignment assignment = optional.get();
        if (shopId.equals("*")) return assignment.scopes().contains("*");
        return scopeMatches(assignment, shopId);
    }

    private boolean canManageMarketScope(CommandSender sender, String shopId) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(MARKET_MANAGE)) return true;
        Optional<GovernanceService.Assignment> optional = governance.assignmentFor(player.getUniqueId());
        if (optional.isEmpty() || optional.get().role() != GovernanceRole.ROYAL_TREASURER) return false;
        GovernanceService.Assignment assignment = optional.get();
        if (shopId.equals("*")) return assignment.scopes().contains("*");
        return scopeMatches(assignment, shopId);
    }

    private void sendMarketHelp(CommandSender sender) {
        sender.sendMessage("§6[CVE Market beta.5-RC1] §fCommands:");
        sender.sendMessage("§f/cve market status");
        sender.sendMessage("§f/cve market list");
        sender.sendMessage("§f/cve market show <id>");
        sender.sendMessage("§f/cve market scarcity <id> <shop|*> <listing|*> <1.0-3.0> <minutes> [announcement]");
        sender.sendMessage("§f/cve market buybonus <id> <shop|*> <listing|*> <1.0-3.0> <minutes> [announcement]");
        sender.sendMessage("§f/cve market discount <id> <shop|*> <listing|*> <0.25-1.0> <minutes> [announcement]");
        sender.sendMessage("§f/cve market end <id> [reason]");
    }

    private boolean returnMarketDenied(CommandSender sender, String action) {
        sender.sendMessage("§c[CVE Market] Akses ditolak untuk " + action + ".");
        return false;
    }

    private static void sendMarket(CommandSender sender, MarketEventService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Market] " + result.message());
    }

    private static String joinTail(String[] tokens, int start) {
        if (tokens == null || start >= tokens.length) return "";
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < tokens.length; index++) {
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(tokens[index]);
        }
        return builder.toString();
    }

    private void handlePricing(CommandSender sender, String[] tokens) {
        if (tokens.length < 3) {
            sendPricingHelp(sender);
            return;
        }

        String action = tokens[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                if (!canViewAny(sender)) {
                    pricingDenied(sender, "view");
                    return;
                }
                sender.sendMessage("§6[CVE Pricing] §f" + pricingAdmin.status());
            }
            case "show" -> {
                if (tokens.length < 5) {
                    sender.sendMessage("§eUsage: /cve pricing show <shop> <listing>");
                    return;
                }
                String shopId = normalize(tokens[3]);
                if (!canViewShop(sender, shopId)) {
                    pricingDenied(sender, "view " + shopId);
                    return;
                }
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
            if (!canViewAny(sender)) {
                pricingDenied(sender, "stats global");
                return;
            }
        } else if (!canViewShop(sender, shopId)) {
            pricingDenied(sender, "stats " + shopId);
            return;
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
        if (!access.allowed()) {
            pricingDenied(sender, "mutate " + shopId);
            return;
        }

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
        if (!canManageGlobal(sender)) {
            pricingDenied(sender, "global mutation");
            return;
        }
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
        if (!canManageGlobal(sender)) {
            pricingDenied(sender, "stability mutation");
            return;
        }
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
        if (amount == null || amount <= 0) return;

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
