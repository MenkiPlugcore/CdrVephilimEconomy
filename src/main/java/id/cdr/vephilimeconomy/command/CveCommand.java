package id.cdr.vephilimeconomy.command;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.diagnostic.DoctorService;
import id.cdr.vephilimeconomy.shop.ListingMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CveCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN = "cdrvephilimeconomy.admin";
    private static final String VIEW = "cdrvephilimeconomy.shop.view";
    private static final String CREATE = "cdrvephilimeconomy.shop.create";
    private static final String DELETE = "cdrvephilimeconomy.shop.delete";
    private static final String BIND = "cdrvephilimeconomy.shop.bind";
    private static final String TOGGLE = "cdrvephilimeconomy.shop.toggle";
    private static final String EDIT = "cdrvephilimeconomy.shop.edit";
    private static final String ITEM = "cdrvephilimeconomy.shop.item";
    private static final String PRICE = "cdrvephilimeconomy.shop.price";
    private static final String STOCK = "cdrvephilimeconomy.shop.stock";
    private static final String MANAGER = "cdrvephilimeconomy.shop.manager";

    private final CdrVephilimEconomy plugin;

    public CveCommand(CdrVephilimEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6CdrVephilimEconomy §7- §f/cve reload §7| §f/cve status §7| §f/cve doctor §7| §f/cve safety §7| §f/cve shop");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("shop")) {
            return handleShop(sender, args);
        }

        if (!has(sender, ADMIN)) {
            sender.sendMessage("§cKamu tidak memiliki permission " + ADMIN + ".");
            return true;
        }

        if (sub.equals("reload")) {
            CdrVephilimEconomy.ReloadResult result = plugin.reloadRuntime();
            sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE] " + result.message());
            return true;
        }

        if (sub.equals("status")) {
            sender.sendMessage("§6[CVE] §f" + plugin.statusSummary());
            return true;
        }

        if (sub.equals("doctor")) {
            DoctorService.Report report = plugin.runDoctor();
            sender.sendMessage("§6[CVE Doctor] §fHealth diagnostics " + plugin.getDescription().getVersion());
            for (DoctorService.Check check : report.checks()) {
                String prefix = switch (check.level()) {
                    case PASS -> "§a[PASS]";
                    case WARN -> "§e[WARN]";
                    case FAIL -> "§c[FAIL]";
                };
                sender.sendMessage(prefix + " §f" + check.name() + " §7- " + check.detail());
            }
            String summaryColor = report.failCount() > 0 ? "§c" : report.warnCount() > 0 ? "§e" : "§a";
            sender.sendMessage("§6[CVE Doctor] §fSummary: §a" + report.passCount() + " PASS §7| §e"
                    + report.warnCount() + " WARN §7| §c" + report.failCount() + " FAIL §7| "
                    + summaryColor + (report.healthy() ? "HEALTHY" : "ATTENTION REQUIRED"));
            return true;
        }

        if (sub.equals("safety")) {
            return handleSafety(sender, args);
        }

        sender.sendMessage("§cSubcommand tidak dikenal. Gunakan /cve reload, /cve status, /cve doctor, /cve safety, atau /cve shop.");
        return true;
    }

    private boolean handleShop(CommandSender sender, String[] args) {
        ShopAdminService service = plugin.shopAdminService();
        if (service == null) {
            sender.sendMessage("§c[CVE Shop] Shop admin runtime belum siap.");
            return true;
        }
        if (args.length == 1) {
            sendShopHelp(sender);
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                if (!require(sender, VIEW)) return true;
                sender.sendMessage("§6[CVE Shop] §fShop runtime:");
                plugin.shopListLines().forEach(sender::sendMessage);
                return true;
            }
            case "info" -> {
                if (!require(sender, VIEW)) return true;
                if (args.length < 3) return usage(sender, "/cve shop info <shop>");
                plugin.shopInfoLines(args[2]).forEach(sender::sendMessage);
                return true;
            }
            case "schema" -> {
                if (!require(sender, VIEW)) return true;
                send(sender, service.schemaStatus());
                return true;
            }
            case "validate" -> {
                if (!require(sender, VIEW)) return true;
                send(sender, service.validateConfig());
                return true;
            }
            case "create" -> {
                if (!require(sender, CREATE)) return true;
                if (args.length < 3) return usage(sender, "/cve shop create <id> [size] [display name]");
                int size = 27;
                int displayStart = 3;
                if (args.length >= 4) {
                    Integer parsed = parseInt(args[3]);
                    if (parsed != null) {
                        size = parsed;
                        displayStart = 4;
                    }
                }
                String display = join(args, displayStart);
                if (display.isBlank()) display = args[2];
                send(sender, service.createShop(sender.getName(), args[2], display, size));
                return true;
            }
            case "delete" -> {
                if (!require(sender, DELETE)) return true;
                if (args.length < 4 || !args[3].equalsIgnoreCase("CONFIRM")) {
                    sender.sendMessage("§cDestructive command. Gunakan /cve shop delete <shop> CONFIRM");
                    return true;
                }
                send(sender, service.deleteShop(sender.getName(), args[2]));
                return true;
            }
            case "name" -> {
                if (!require(sender, EDIT)) return true;
                if (args.length < 4) return usage(sender, "/cve shop name <shop> <display name>");
                send(sender, service.setDisplayName(sender.getName(), args[2], join(args, 3)));
                return true;
            }
            case "size" -> {
                if (!require(sender, EDIT)) return true;
                if (args.length < 4) return usage(sender, "/cve shop size <shop> <9|18|27|36|45|54>");
                Integer size = parseInt(args[3]);
                if (size == null) return invalidNumber(sender, args[3]);
                send(sender, service.setSize(sender.getName(), args[2], size));
                return true;
            }
            case "bind" -> {
                if (!require(sender, BIND)) return true;
                if (args.length < 4) return usage(sender, "/cve shop bind <shop> <npc-id|-1>");
                Integer npcId = parseInt(args[3]);
                if (npcId == null) return invalidNumber(sender, args[3]);
                send(sender, service.bindNpc(sender.getName(), args[2], npcId));
                return true;
            }
            case "enable", "disable" -> {
                if (!require(sender, TOGGLE)) return true;
                if (args.length < 3) return usage(sender, "/cve shop " + action + " <shop>");
                send(sender, service.setEnabled(sender.getName(), args[2], action.equals("enable")));
                return true;
            }
            case "manager" -> {
                if (!require(sender, MANAGER)) return true;
                if (args.length < 4) return usage(sender, "/cve shop manager <shop> <name|none>");
                send(sender, service.setManager(sender.getName(), args[2], join(args, 3)));
                return true;
            }
            case "additem" -> {
                if (!require(sender, ITEM)) return true;
                if (args.length < 11) {
                    return usage(sender, "/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>");
                }
                Material material = resolveMaterial(sender, args[4]);
                if (material == null) {
                    sender.sendMessage("§cMaterial tidak valid atau item di tangan kosong.");
                    return true;
                }
                Integer slot = parseInt(args[5]);
                ListingMode mode = parseMode(args[6]);
                Double buy = parseDouble(args[7]);
                Double sell = parseDouble(args[8]);
                Integer initial = parseInt(args[9]);
                Integer max = parseInt(args[10]);
                if (slot == null || mode == null || buy == null || sell == null || initial == null || max == null) {
                    sender.sendMessage("§cParameter additem invalid. Mode: BUY/SELL/BUY_SELL; angka wajib valid.");
                    return true;
                }
                send(sender, service.addItem(sender.getName(), args[2], args[3], material, slot, mode, buy, sell, initial, max));
                return true;
            }
            case "removeitem" -> {
                if (!require(sender, ITEM)) return true;
                if (args.length < 5 || !args[4].equalsIgnoreCase("CONFIRM")) {
                    sender.sendMessage("§cDestructive command. Gunakan /cve shop removeitem <shop> <listing> CONFIRM");
                    return true;
                }
                send(sender, service.removeItem(sender.getName(), args[2], args[3]));
                return true;
            }
            case "price" -> {
                if (!require(sender, PRICE)) return true;
                if (args.length < 6) return usage(sender, "/cve shop price <shop> <listing> <buy|sell> <value>");
                boolean buySide;
                if (args[4].equalsIgnoreCase("buy")) buySide = true;
                else if (args[4].equalsIgnoreCase("sell")) buySide = false;
                else return usage(sender, "Side harus buy atau sell.");
                Double price = parseDouble(args[5]);
                if (price == null) return invalidNumber(sender, args[5]);
                send(sender, service.setPrice(sender.getName(), args[2], args[3], buySide, price));
                return true;
            }
            case "mode" -> {
                if (!require(sender, ITEM)) return true;
                if (args.length < 5) return usage(sender, "/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>");
                ListingMode mode = parseMode(args[4]);
                if (mode == null) return usage(sender, "Mode harus BUY, SELL, atau BUY_SELL.");
                send(sender, service.setMode(sender.getName(), args[2], args[3], mode));
                return true;
            }
            case "slot" -> {
                if (!require(sender, ITEM)) return true;
                if (args.length < 5) return usage(sender, "/cve shop slot <shop> <listing> <slot>");
                Integer slot = parseInt(args[4]);
                if (slot == null) return invalidNumber(sender, args[4]);
                send(sender, service.setSlot(sender.getName(), args[2], args[3], slot));
                return true;
            }
            case "initialstock", "maxstock" -> {
                if (!require(sender, STOCK)) return true;
                if (args.length < 5) return usage(sender, "/cve shop " + action + " <shop> <listing> <value>");
                Integer value = parseInt(args[4]);
                if (value == null) return invalidNumber(sender, args[4]);
                ShopAdminService.Result result = action.equals("initialstock")
                        ? service.setInitialStock(sender.getName(), args[2], args[3], value)
                        : service.setMaxStock(sender.getName(), args[2], args[3], value);
                send(sender, result);
                return true;
            }
            case "stock" -> {
                if (!require(sender, STOCK)) return true;
                if (args.length < 6) return usage(sender, "/cve shop stock <shop> <listing> <set|add|remove> <amount>");
                ShopAdminService.StockOperation operation;
                try {
                    operation = ShopAdminService.StockOperation.valueOf(args[4].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    return usage(sender, "Operation harus set, add, atau remove.");
                }
                Integer value = parseInt(args[5]);
                if (value == null) return invalidNumber(sender, args[5]);
                send(sender, service.changeRuntimeStock(sender.getName(), args[2], args[3], operation, value));
                return true;
            }
            default -> {
                sendShopHelp(sender);
                return true;
            }
        }
    }

    private boolean handleSafety(CommandSender sender, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("§6[CVE Safety] §f" + plugin.safetyStatusSummary());
            return true;
        }

        if (args[1].equalsIgnoreCase("unlock")) {
            if (args.length < 3 || !args[2].equalsIgnoreCase("CONFIRM")) {
                sender.sendMessage("§c[CVE] Recovery tidak dijalankan. Setelah memeriksa saldo, item, stock, audit log, dan transaction ID, gunakan:");
                sender.sendMessage("§e/cve safety unlock CONFIRM");
                return true;
            }

            CdrVephilimEconomy.ReloadResult result = plugin.unlockSafety(sender.getName());
            sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Safety] " + result.message());
            return true;
        }

        sender.sendMessage("§cGunakan /cve safety status atau /cve safety unlock CONFIRM.");
        return true;
    }

    private void sendShopHelp(CommandSender sender) {
        sender.sendMessage("§6[CVE Shop] §fBeta.2 RC2 management commands:");
        sender.sendMessage("§e/cve shop list §7| §e/cve shop info <shop> §7| §e/cve shop schema §7| §e/cve shop validate");
        sender.sendMessage("§e/cve shop create <id> [size] [display name] §7| §e/cve shop delete <id> CONFIRM");
        sender.sendMessage("§e/cve shop name <shop> <display name> §7| §e/cve shop size <shop> <size>");
        sender.sendMessage("§e/cve shop bind <shop> <npc-id|-1> §7| §e/cve shop enable|disable <shop>");
        sender.sendMessage("§e/cve shop manager <shop> <name|none>");
        sender.sendMessage("§e/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>");
        sender.sendMessage("§e/cve shop removeitem <shop> <listing> CONFIRM §7| §e/cve shop mode <shop> <listing> <mode>");
        sender.sendMessage("§e/cve shop slot <shop> <listing> <slot> §7| §e/cve shop price <shop> <listing> <buy|sell> <value>");
        sender.sendMessage("§e/cve shop initialstock|maxstock <shop> <listing> <value>");
        sender.sendMessage("§e/cve shop stock <shop> <listing> <set|add|remove> <amount>");
    }

    private static Material resolveMaterial(CommandSender sender, String raw) {
        if (raw.equalsIgnoreCase("hand")) {
            if (!(sender instanceof Player player)) return null;
            ItemStack stack = player.getInventory().getItemInMainHand();
            return stack == null || stack.getType().isAir() ? null : stack.getType();
        }
        Material material = Material.matchMaterial(raw);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }

    private static ListingMode parseMode(String raw) {
        try {
            return ListingMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Double parseDouble(String raw) {
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String join(String[] args, int start) {
        if (start >= args.length) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private boolean require(CommandSender sender, String permission) {
        if (has(sender, permission)) return true;
        sender.sendMessage("§cKamu tidak memiliki permission " + permission + ".");
        return false;
    }

    private static boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(ADMIN) || sender.hasPermission(permission);
    }

    private static void send(CommandSender sender, ShopAdminService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Shop] " + result.message());
    }

    private static boolean usage(CommandSender sender, String message) {
        sender.sendMessage("§e[CVE Shop] " + message);
        return true;
    }

    private static boolean invalidNumber(CommandSender sender, String raw) {
        sender.sendMessage("§cAngka tidak valid: " + raw);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            if (has(sender, ADMIN)) {
                addIfStarts(result, "reload", input);
                addIfStarts(result, "status", input);
                addIfStarts(result, "doctor", input);
                addIfStarts(result, "safety", input);
            }
            if (hasAnyShopPermission(sender)) addIfStarts(result, "shop", input);
            return result;
        }

        if (args[0].equalsIgnoreCase("shop")) {
            return completeShop(sender, args);
        }

        if (!has(sender, ADMIN)) return Collections.emptyList();

        if (args.length == 2 && args[0].equalsIgnoreCase("safety")) {
            return matches(args[1], List.of("status", "unlock"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("safety") && args[1].equalsIgnoreCase("unlock")) {
            return matches(args[2], List.of("CONFIRM"));
        }
        return Collections.emptyList();
    }

    private List<String> completeShop(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> actions = new ArrayList<>();
            if (has(sender, VIEW)) { actions.add("list"); actions.add("info"); actions.add("schema"); actions.add("validate"); }
            if (has(sender, CREATE)) actions.add("create");
            if (has(sender, DELETE)) actions.add("delete");
            if (has(sender, EDIT)) { actions.add("name"); actions.add("size"); }
            if (has(sender, BIND)) actions.add("bind");
            if (has(sender, TOGGLE)) { actions.add("enable"); actions.add("disable"); }
            if (has(sender, MANAGER)) actions.add("manager");
            if (has(sender, ITEM)) { actions.add("additem"); actions.add("removeitem"); actions.add("mode"); actions.add("slot"); }
            if (has(sender, PRICE)) actions.add("price");
            if (has(sender, STOCK)) { actions.add("initialstock"); actions.add("maxstock"); actions.add("stock"); }
            return matches(args[1], actions);
        }

        if (args.length >= 3) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && !action.equals("create") && !action.equals("list")
                    && !action.equals("schema") && !action.equals("validate")) {
                return matches(args[2], plugin.runtimeShopIds());
            }
            if (args.length == 4 && List.of("removeitem", "price", "mode", "slot", "initialstock", "maxstock", "stock").contains(action)) {
                return matches(args[3], plugin.runtimeListingIds(args[2]));
            }
            if (args.length == 4 && action.equals("delete")) return matches(args[3], List.of("CONFIRM"));
            if (args.length == 4 && action.equals("size")) return matches(args[3], List.of("9", "18", "27", "36", "45", "54"));
            if (args.length == 5 && action.equals("removeitem")) return matches(args[4], List.of("CONFIRM"));
            if (args.length == 5 && action.equals("price")) return matches(args[4], List.of("buy", "sell"));
            if (args.length == 5 && action.equals("mode")) return matches(args[4], List.of("BUY", "SELL", "BUY_SELL"));
            if (args.length == 5 && action.equals("stock")) return matches(args[4], List.of("set", "add", "remove"));
            if (action.equals("additem")) {
                if (args.length == 5) return matches(args[4], List.of("hand", "COAL", "IRON_INGOT", "GOLD_INGOT", "DIAMOND"));
                if (args.length == 7) return matches(args[6], List.of("BUY", "SELL", "BUY_SELL"));
            }
        }
        return Collections.emptyList();
    }

    private static boolean hasAnyShopPermission(CommandSender sender) {
        return has(sender, VIEW) || has(sender, CREATE) || has(sender, DELETE) || has(sender, BIND)
                || has(sender, TOGGLE) || has(sender, EDIT) || has(sender, ITEM) || has(sender, PRICE)
                || has(sender, STOCK) || has(sender, MANAGER);
    }

    private static List<String> matches(String inputRaw, List<String> values) {
        String input = inputRaw.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) addIfStarts(result, value, input);
        return result;
    }

    private static void addIfStarts(List<String> target, String value, String input) {
        if (value.toLowerCase(Locale.ROOT).startsWith(input)) target.add(value);
    }
}
