package id.cdr.vephilimeconomy.market;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Dedicated beta.5 RC2 command interceptor for /cve market supply.
 *
 * Registered at LOWEST so it consumes the nested supply command before the
 * existing beta.5 market command interceptor handles generic /cve market.
 */
public final class MarketSupplyCommandListener implements Listener {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String MARKET_VIEW = "cdrvephilimeconomy.market.view";
    private static final String MARKET_MANAGE = "cdrvephilimeconomy.market.manage";

    private final CdrVephilimEconomy plugin;
    private final MarketSupplyService supply;
    private final File governanceFile;

    public MarketSupplyCommandListener(CdrVephilimEconomy plugin, AdminAuditService audit) {
        this.plugin = plugin;
        this.supply = new MarketSupplyService(plugin, audit);
        this.governanceFile = new File(plugin.getDataFolder(), "governance.yml");
        try {
            supply.load();
        } catch (IOException exception) {
            plugin.getLogger().severe("Beta.5 RC2 supply runtime tidak sehat: " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String[] tokens = event.getMessage().trim().split("\\s+");
        if (!isSupplyCommand(tokens)) return;
        event.setCancelled(true);
        handle(event.getPlayer(), tokens);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String raw = event.getCommand() == null ? "" : event.getCommand().trim();
        String[] tokens = raw.split("\\s+");
        if (!isSupplyCommand(tokens)) return;
        event.setCancelled(true);
        handle(event.getSender(), tokens);
    }

    private void handle(CommandSender sender, String[] tokens) {
        try {
            supply.load();
        } catch (IOException exception) {
            sender.sendMessage("§c[CVE Supply] market-supply.yml tidak sehat: " + exception.getMessage());
            return;
        }

        if (tokens.length < 4) {
            sendHelp(sender);
            return;
        }
        String action = tokens[3].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                if (!canViewAny(sender)) returnDenied(sender, "status");
                sender.sendMessage("§6[CVE Supply] §f" + supply.statusSummary());
            }
            case "list" -> {
                if (!canViewAny(sender)) {
                    returnDenied(sender, "list");
                    return;
                }
                boolean any = false;
                sender.sendMessage("§6[CVE Supply] §fLedger:");
                for (MarketSupplyService.SupplyEntry entry : supply.all()) {
                    if (!canViewScope(sender, entry.shopId())) continue;
                    any = true;
                    sender.sendMessage("§f- " + entry.id()
                            + " §7| " + entry.state()
                            + " §7| scope=§f" + entry.shopId() + "/" + entry.listingId()
                            + " §7| +§f" + entry.amount()
                            + " §7| stock=§f" + entry.beforeStock() + "->" + entry.afterStock());
                }
                if (!any) sender.sendMessage("§7Tidak ada supply event yang dapat kamu lihat.");
            }
            case "show" -> {
                if (tokens.length < 5) {
                    sender.sendMessage("§eUsage: /cve market supply show <id>");
                    return;
                }
                MarketSupplyService.SupplyEntry entry = supply.find(tokens[4]).orElse(null);
                if (entry == null) {
                    sender.sendMessage("§c[CVE Supply] Supply event tidak ditemukan: " + tokens[4]);
                    return;
                }
                if (!canViewScope(sender, entry.shopId())) {
                    returnDenied(sender, "show " + entry.id());
                    return;
                }
                supply.showLines(tokens[4]).forEach(sender::sendMessage);
            }
            case "create" -> handleCreate(sender, tokens);
            case "recover" -> handleRecover(sender, tokens);
            default -> sendHelp(sender);
        }
    }

    private void handleCreate(CommandSender sender, String[] tokens) {
        if (tokens.length < 8) {
            sender.sendMessage("§eUsage: /cve market supply create <id> <shop> <listing> <amount> [announcement]");
            return;
        }
        String shopId = normalize(tokens[5]);
        if (shopId.equals("*") || normalize(tokens[6]).equals("*")) {
            sender.sendMessage("§c[CVE Supply] Supply stock harus menargetkan satu shop dan satu listing konkret.");
            return;
        }
        if (!canManageScope(sender, shopId)) {
            returnDenied(sender, "create " + shopId);
            return;
        }
        Integer amount = parseInt(tokens[7]);
        if (amount == null || amount <= 0) {
            sender.sendMessage("§c[CVE Supply] amount harus integer > 0.");
            return;
        }
        MarketSupplyService.Result result = supply.create(
                sender.getName(), tokens[4], tokens[5], tokens[6], amount, joinTail(tokens, 8));
        send(sender, result);
    }

    private void handleRecover(CommandSender sender, String[] tokens) {
        if (!canRecover(sender)) {
            returnDenied(sender, "recovery");
            return;
        }
        if (tokens.length < 7 || !tokens[6].equalsIgnoreCase("CONFIRM")) {
            sender.sendMessage("§eUsage: /cve market supply recover <id> <applied|not-applied> CONFIRM");
            return;
        }
        MarketSupplyService.RecoveryDecision decision;
        String raw = tokens[5].toLowerCase(Locale.ROOT);
        if (raw.equals("applied")) {
            decision = MarketSupplyService.RecoveryDecision.APPLIED;
        } else if (raw.equals("not-applied") || raw.equals("not_applied") || raw.equals("notapplied")) {
            decision = MarketSupplyService.RecoveryDecision.NOT_APPLIED;
        } else {
            sender.sendMessage("§c[CVE Supply] Recovery mode harus applied atau not-applied.");
            return;
        }
        send(sender, supply.recover(sender.getName(), tokens[4], decision));
    }

    private boolean canViewAny(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(MARKET_VIEW)
                || player.hasPermission(MARKET_MANAGE)) return true;
        return governanceAssignment(player.getUniqueId()) != null;
    }

    private boolean canViewScope(CommandSender sender, String shopId) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(MARKET_VIEW)
                || player.hasPermission(MARKET_MANAGE)) return true;
        GovernanceAssignment assignment = governanceAssignment(player.getUniqueId());
        return assignment != null && assignment.matches(shopId);
    }

    private boolean canManageScope(CommandSender sender, String shopId) {
        if (!(sender instanceof Player player)) return true;
        if (player.hasPermission(ADMIN) || player.hasPermission(MARKET_MANAGE)) return true;
        GovernanceAssignment assignment = governanceAssignment(player.getUniqueId());
        return assignment != null
                && assignment.role().equals("ROYAL_TREASURER")
                && assignment.matches(shopId);
    }

    private boolean canRecover(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        return player.hasPermission(ADMIN) || player.hasPermission(MARKET_MANAGE);
    }

    private GovernanceAssignment governanceAssignment(UUID uuid) {
        if (uuid == null || !governanceFile.isFile()) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(governanceFile);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Supply governance read gagal: " + exception.getMessage());
            return null;
        }
        if (yaml.getInt("meta.schema", -1) != 1) return null;
        ConfigurationSection section = yaml.getConfigurationSection("members." + uuid);
        if (section == null) return null;
        String role = section.getString("role", "").trim().toUpperCase(Locale.ROOT);
        List<String> scopes = section.getStringList("scopes").stream()
                .map(MarketSupplyCommandListener::normalize)
                .toList();
        if (role.isBlank() || scopes.isEmpty()) return null;
        return new GovernanceAssignment(role, scopes);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6[CVE Supply beta.5-RC2] §fCommands:");
        sender.sendMessage("§f/cve market supply status");
        sender.sendMessage("§f/cve market supply list");
        sender.sendMessage("§f/cve market supply show <id>");
        sender.sendMessage("§f/cve market supply create <id> <shop> <listing> <amount> [announcement]");
        sender.sendMessage("§f/cve market supply recover <id> <applied|not-applied> CONFIRM");
    }

    private void returnDenied(CommandSender sender, String action) {
        sender.sendMessage("§c[CVE Supply] Akses ditolak untuk " + action + ".");
    }

    private static void send(CommandSender sender, MarketSupplyService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Supply] " + result.message());
    }

    private static boolean isSupplyCommand(String[] tokens) {
        if (tokens.length < 3 || !isCveRoot(tokens[0])) return false;
        return tokens[1].equalsIgnoreCase("market") && tokens[2].equalsIgnoreCase("supply");
    }

    private static boolean isCveRoot(String raw) {
        String root = raw == null ? "" : raw.trim();
        if (root.startsWith("/")) root = root.substring(1);
        root = root.toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        if (namespace >= 0 && namespace + 1 < root.length()) root = root.substring(namespace + 1);
        return root.equals("cve") || root.equals("veconomy");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String joinTail(String[] tokens, int start) {
        if (tokens == null || start >= tokens.length) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < tokens.length; i++) {
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(tokens[i]);
        }
        return builder.toString();
    }

    private record GovernanceAssignment(String role, List<String> scopes) {
        private boolean matches(String shopId) {
            String normalized = normalize(shopId);
            return scopes.contains("*") || scopes.contains(normalized);
        }
    }
}
