package id.cdr.vephilimeconomy.discount;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public final class PlayerDiscountCommandListener implements Listener {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String VIEW = "cdrvephilimeconomy.discount.view";
    private static final String MANAGE = "cdrvephilimeconomy.discount.manage";

    private final CdrVephilimEconomy plugin;
    private final PlayerDiscountService service;

    public PlayerDiscountCommandListener(CdrVephilimEconomy plugin, PlayerDiscountService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        String[] split = tokenize(raw);
        if (!isDiscountCommand(split)) {
            return;
        }
        event.setCancelled(true);
        handle(event.getPlayer(), Arrays.copyOfRange(split, 2, split.length));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsoleCommand(ServerCommandEvent event) {
        String[] split = tokenize(event.getCommand());
        if (!isDiscountCommand(split)) {
            return;
        }
        event.setCancelled(true);
        handle(event.getSender(), Arrays.copyOfRange(split, 2, split.length));
    }

    private void handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                if (!canView(sender)) return;
                sender.sendMessage("§6[CVE Discount] §f" + service.statusSummary());
            }
            case "list" -> {
                if (!canView(sender)) return;
                sender.sendMessage("§6[CVE Discount] §fPersonal discounts:");
                service.listLines().forEach(sender::sendMessage);
            }
            case "show" -> {
                if (!canView(sender)) return;
                if (args.length < 2) {
                    usage(sender, "/cve discount show <player|uuid>");
                    return;
                }
                Target target = resolve(args[1]);
                if (target == null) {
                    sender.sendMessage("§c[CVE Discount] Player tidak ditemukan. Gunakan player yang pernah join atau UUID.");
                    return;
                }
                sender.sendMessage("§6[CVE Discount] §f" + service.describe(target.uuid(), target.name()));
            }
            case "set" -> {
                if (!canManage(sender)) return;
                if (args.length < 4) {
                    usage(sender, "/cve discount set <player|uuid> <shop|*> <percent>");
                    return;
                }
                Target target = resolve(args[1]);
                if (target == null) {
                    sender.sendMessage("§c[CVE Discount] Player tidak ditemukan. Gunakan player yang pernah join atau UUID.");
                    return;
                }
                String scope = args[2].toLowerCase(Locale.ROOT);
                if (!scope.equals("*") && plugin.findRuntimeShop(scope).isEmpty()) {
                    sender.sendMessage("§c[CVE Discount] Shop tidak ditemukan: " + scope);
                    return;
                }
                Double percent = parseDouble(args[3]);
                if (percent == null) {
                    sender.sendMessage("§c[CVE Discount] Percent harus angka > 0 sampai "
                            + PlayerDiscountService.MAX_DISCOUNT_PERCENT + ".");
                    return;
                }
                send(sender, service.set(sender.getName(), target.uuid(), target.name(), scope, percent));
            }
            case "remove" -> {
                if (!canManage(sender)) return;
                if (args.length < 3) {
                    usage(sender, "/cve discount remove <player|uuid> <shop|*>");
                    return;
                }
                Target target = resolve(args[1]);
                if (target == null) {
                    sender.sendMessage("§c[CVE Discount] Player tidak ditemukan. Gunakan player yang pernah join atau UUID.");
                    return;
                }
                send(sender, service.remove(sender.getName(), target.uuid(), target.name(), args[2]));
            }
            case "clear" -> {
                if (!canManage(sender)) return;
                if (args.length < 3 || !args[2].equalsIgnoreCase("CONFIRM")) {
                    usage(sender, "/cve discount clear <player|uuid> CONFIRM");
                    return;
                }
                Target target = resolve(args[1]);
                if (target == null) {
                    sender.sendMessage("§c[CVE Discount] Player tidak ditemukan. Gunakan player yang pernah join atau UUID.");
                    return;
                }
                send(sender, service.clear(sender.getName(), target.uuid(), target.name()));
            }
            case "reload" -> {
                if (!canManage(sender)) return;
                send(sender, service.load());
            }
            default -> sendHelp(sender);
        }
    }

    private boolean canView(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true;
        }
        if (sender.hasPermission(ADMIN) || sender.hasPermission(VIEW) || sender.hasPermission(MANAGE)) {
            return true;
        }
        sender.sendMessage("§c[CVE Discount] Kamu tidak memiliki permission untuk melihat personal discount.");
        return false;
    }

    private boolean canManage(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true;
        }
        if (sender.hasPermission(ADMIN) || sender.hasPermission(MANAGE)) {
            return true;
        }
        sender.sendMessage("§c[CVE Discount] Kamu tidak memiliki permission " + MANAGE + ".");
        return false;
    }

    private void sendHelp(CommandSender sender) {
        if (!canView(sender)) {
            return;
        }
        sender.sendMessage("§6[CVE Discount] §fPer-player BUY discount (admin/op)");
        sender.sendMessage("§f/cve discount status");
        sender.sendMessage("§f/cve discount list");
        sender.sendMessage("§f/cve discount show <player|uuid>");
        sender.sendMessage("§f/cve discount set <player|uuid> <shop|*> <percent>");
        sender.sendMessage("§f/cve discount remove <player|uuid> <shop|*>");
        sender.sendMessage("§f/cve discount clear <player|uuid> CONFIRM");
        sender.sendMessage("§f/cve discount reload");
        sender.sendMessage("§7Discount hanya mengurangi harga BUY. SELL tidak berubah. Maksimum "
                + PlayerDiscountService.MAX_DISCOUNT_PERCENT + "%.");
    }

    private Target resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(raw);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String name = offline.getName() == null ? uuid.toString() : offline.getName();
            return new Target(uuid, name);
        } catch (IllegalArgumentException ignored) {
        }

        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName());
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(raw)) {
                return new Target(offline.getUniqueId(), offline.getName());
            }
        }
        return null;
    }

    private static boolean isDiscountCommand(String[] split) {
        if (split.length < 2) {
            return false;
        }
        String root = split[0].toLowerCase(Locale.ROOT);
        return (root.equals("cve") || root.equals("veconomy"))
                && split[1].equalsIgnoreCase("discount");
    }

    private static String[] tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return raw.trim().split("\\s+");
    }

    private static Double parseDouble(String raw) {
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void send(CommandSender sender, PlayerDiscountService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Discount] " + result.message());
    }

    private static void usage(CommandSender sender, String usage) {
        sender.sendMessage("§e[CVE Discount] Usage: " + usage);
    }

    private record Target(UUID uuid, String name) {
    }
}
