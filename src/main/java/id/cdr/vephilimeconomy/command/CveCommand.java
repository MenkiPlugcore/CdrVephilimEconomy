package id.cdr.vephilimeconomy.command;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.diagnostic.DoctorService;
import id.cdr.vephilimeconomy.governance.GovernanceApprovalService;
import id.cdr.vephilimeconomy.governance.GovernanceCapability;
import id.cdr.vephilimeconomy.governance.GovernanceRole;
import id.cdr.vephilimeconomy.governance.GovernanceService;
import id.cdr.vephilimeconomy.shop.ListingMode;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
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
import java.util.Optional;

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
    private final GovernanceService governance;
    private final GovernanceApprovalService approvals;

    public CveCommand(CdrVephilimEconomy plugin) {
        this.plugin = plugin;
        this.governance = new GovernanceService(plugin, plugin.adminAuditService());
        GovernanceService.Result loaded = governance.load();
        if (!loaded.success()) {
            plugin.getLogger().severe("Beta.3 governance runtime tidak sehat: " + loaded.message());
        }
        this.approvals = new GovernanceApprovalService(plugin, governance);
        GovernanceApprovalService.Result approvalLoaded = approvals.load();
        if (!approvalLoaded.success()) {
            plugin.getLogger().severe("Beta.3 approval runtime tidak sehat: " + approvalLoaded.message());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6CdrVephilimEconomy §7- §f/cve reload §7| §f/cve status §7| §f/cve doctor §7| §f/cve safety §7| §f/cve shop §7| §f/cve governance");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("shop")) return handleShop(sender, args);
        if (sub.equals("governance")) return handleGovernance(sender, args);

        if (!hasAdmin(sender)) {
            sender.sendMessage("§cKamu tidak memiliki permission " + ADMIN + ".");
            return true;
        }

        switch (sub) {
            case "reload" -> {
                CdrVephilimEconomy.ReloadResult result = plugin.reloadRuntime();
                sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE] " + result.message());
                return true;
            }
            case "status" -> {
                sender.sendMessage("§6[CVE] §f" + plugin.statusSummary());
                sender.sendMessage("§6[CVE Governance] §f" + governance.statusSummary());
                sender.sendMessage("§6[CVE Approval] §f" + approvals.statusSummary());
                return true;
            }
            case "doctor" -> {
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
                sender.sendMessage("§6[CVE Governance] §f" + governance.statusSummary());
                sender.sendMessage("§6[CVE Approval] §f" + approvals.statusSummary());
                return true;
            }
            case "safety" -> {
                return handleSafety(sender, args);
            }
            default -> {
                sender.sendMessage("§cSubcommand tidak dikenal. Gunakan /cve reload, /cve status, /cve doctor, /cve safety, /cve shop, atau /cve governance.");
                return true;
            }
        }
    }

    private boolean handleGovernance(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sendGovernanceHelp(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                if (!governance.canViewGovernance(sender)) return governanceDenied(sender);
                sender.sendMessage("§6[CVE Governance] §f" + governance.statusSummary());
                sender.sendMessage("§6[CVE Approval] §f" + approvals.statusSummary());
                return true;
            }
            case "list" -> {
                if (!governance.canViewGovernance(sender)) return governanceDenied(sender);
                sender.sendMessage("§6[CVE Governance] §fAssignments:");
                governance.listLines().forEach(sender::sendMessage);
                return true;
            }
            case "who" -> {
                if (!governance.canViewGovernance(sender)) return governanceDenied(sender);
                if (args.length < 3) return governanceUsage(sender, "/cve governance who <player>");
                sender.sendMessage("§6[CVE Governance] §f" + governance.describe(args[2]));
                return true;
            }
            case "grant" -> {
                if (!governance.canAdminGovernance(sender)) return governanceDenied(sender);
                if (args.length < 5) {
                    return governanceUsage(sender, "/cve governance grant <player> <ECONOMY_STAFF|ECONOMY_MANAGER|ROYAL_TREASURER> <shop|*>");
                }
                GovernanceRole role;
                try {
                    role = GovernanceRole.valueOf(args[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    return governanceUsage(sender, "Role harus ECONOMY_STAFF, ECONOMY_MANAGER, atau ROYAL_TREASURER.");
                }
                send(sender, governance.grant(sender.getName(), args[2], role, args[4]));
                return true;
            }
            case "revoke" -> {
                if (!governance.canAdminGovernance(sender)) return governanceDenied(sender);
                if (args.length < 4) return governanceUsage(sender, "/cve governance revoke <player> <shop|*|all>");
                send(sender, governance.revoke(sender.getName(), args[2], args[3]));
                return true;
            }
            case "reload" -> {
                if (!governance.canAdminGovernance(sender)) return governanceDenied(sender);
                send(sender, governance.reload());
                return true;
            }
            case "approval" -> {
                return handleApproval(sender, args);
            }
            default -> {
                sendGovernanceHelp(sender);
                return true;
            }
        }
    }

    private boolean handleApproval(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendApprovalHelp(sender);
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                if (!approvals.canView(sender)) return governanceDenied(sender);
                sender.sendMessage("§6[CVE Approval] §f" + approvals.statusSummary());
                return true;
            }
            case "list" -> {
                if (!approvals.canView(sender)) return governanceDenied(sender);
                sender.sendMessage("§6[CVE Approval] §fPending/executing:");
                approvals.listLines(sender).forEach(sender::sendMessage);
                return true;
            }
            case "show" -> {
                if (!approvals.canView(sender)) return governanceDenied(sender);
                if (args.length < 4) return governanceUsage(sender, "/cve governance approval show <id>");
                sender.sendMessage("§6[CVE Approval] §f" + approvals.describe(sender, args[3]));
                return true;
            }
            case "approve" -> {
                if (args.length < 4) return governanceUsage(sender, "/cve governance approval approve <id>");
                send(sender, approvals.approve(sender, args[3]));
                return true;
            }
            case "reject" -> {
                if (args.length < 4) return governanceUsage(sender, "/cve governance approval reject <id> [reason]");
                send(sender, approvals.reject(sender, args[3], join(args, 4)));
                return true;
            }
            case "cancel" -> {
                if (args.length < 4) return governanceUsage(sender, "/cve governance approval cancel <id>");
                send(sender, approvals.cancel(sender, args[3]));
                return true;
            }
            case "reload" -> {
                if (!governance.canAdminGovernance(sender)) return governanceDenied(sender);
                send(sender, approvals.reload());
                return true;
            }
            case "recover" -> {
                if (!governance.canAdminGovernance(sender)) return governanceDenied(sender);
                if (args.length < 6 || !args[5].equalsIgnoreCase("CONFIRM")) {
                    return governanceUsage(sender, "/cve governance approval recover <id> <executed|not-executed> CONFIRM");
                }
                boolean executed;
                if (args[4].equalsIgnoreCase("executed")) executed = true;
                else if (args[4].equalsIgnoreCase("not-executed")) executed = false;
                else return governanceUsage(sender, "Recovery state harus executed atau not-executed.");
                send(sender, approvals.recover(sender, args[3], executed));
                return true;
            }
            default -> {
                sendApprovalHelp(sender);
                return true;
            }
        }
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
                if (!requireShop(sender, VIEW, GovernanceCapability.VIEW, null)) return true;
                sender.sendMessage("§6[CVE Shop] §fShop runtime:");
                plugin.shopListLines().forEach(sender::sendMessage);
                return true;
            }
            case "info" -> {
                if (args.length < 3) return usage(sender, "/cve shop info <shop>");
                if (!requireShop(sender, VIEW, GovernanceCapability.VIEW, args[2])) return true;
                plugin.shopInfoLines(args[2]).forEach(sender::sendMessage);
                return true;
            }
            case "schema" -> {
                if (!requireShop(sender, VIEW, GovernanceCapability.VIEW, null)) return true;
                send(sender, service.schemaStatus());
                return true;
            }
            case "validate" -> {
                if (!requireShop(sender, VIEW, GovernanceCapability.VIEW, null)) return true;
                send(sender, service.validateConfig());
                return true;
            }
            case "create" -> {
                if (!requireShop(sender, CREATE, GovernanceCapability.CREATE, "*")) return true;
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
                if (args.length < 3) return usage(sender, "/cve shop delete <shop> CONFIRM");
                if (!requireShop(sender, DELETE, GovernanceCapability.DELETE, args[2])) return true;
                if (args.length < 4 || !args[3].equalsIgnoreCase("CONFIRM")) {
                    sender.sendMessage("§cDestructive command. Gunakan /cve shop delete <shop> CONFIRM");
                    return true;
                }
                send(sender, service.deleteShop(sender.getName(), args[2]));
                return true;
            }
            case "name" -> {
                if (args.length < 4) return usage(sender, "/cve shop name <shop> <display name>");
                if (!requireShop(sender, EDIT, GovernanceCapability.EDIT, args[2])) return true;
                send(sender, service.setDisplayName(sender.getName(), args[2], join(args, 3)));
                return true;
            }
            case "size" -> {
                if (args.length < 4) return usage(sender, "/cve shop size <shop> <9|18|27|36|45|54>");
                if (!requireShop(sender, EDIT, GovernanceCapability.EDIT, args[2])) return true;
                Integer size = parseInt(args[3]);
                if (size == null) return invalidNumber(sender, args[3]);
                send(sender, service.setSize(sender.getName(), args[2], size));
                return true;
            }
            case "bind" -> {
                if (args.length < 4) return usage(sender, "/cve shop bind <shop> <npc-id|-1>");
                if (!requireShop(sender, BIND, GovernanceCapability.BIND, args[2])) return true;
                Integer npcId = parseInt(args[3]);
                if (npcId == null) return invalidNumber(sender, args[3]);
                send(sender, service.bindNpc(sender.getName(), args[2], npcId));
                return true;
            }
            case "enable", "disable" -> {
                if (args.length < 3) return usage(sender, "/cve shop " + action + " <shop>");
                if (!requireShop(sender, TOGGLE, GovernanceCapability.TOGGLE, args[2])) return true;
                send(sender, service.setEnabled(sender.getName(), args[2], action.equals("enable")));
                return true;
            }
            case "manager" -> {
                if (args.length < 4) return usage(sender, "/cve shop manager <shop> <name|none>");
                if (!requireShop(sender, MANAGER, GovernanceCapability.MANAGER, args[2])) return true;
                send(sender, service.setManager(sender.getName(), args[2], join(args, 3)));
                return true;
            }
            case "additem" -> {
                if (args.length < 11) return usage(sender, "/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>");
                if (!requireShop(sender, ITEM, GovernanceCapability.ITEM, args[2])) return true;
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
                if (args.length < 4) return usage(sender, "/cve shop removeitem <shop> <listing> CONFIRM");
                if (!requireShop(sender, ITEM, GovernanceCapability.ITEM, args[2])) return true;
                if (args.length < 5 || !args[4].equalsIgnoreCase("CONFIRM")) {
                    sender.sendMessage("§cDestructive command. Gunakan /cve shop removeitem <shop> <listing> CONFIRM");
                    return true;
                }
                send(sender, service.removeItem(sender.getName(), args[2], args[3]));
                return true;
            }
            case "price" -> {
                if (args.length < 6) return usage(sender, "/cve shop price <shop> <listing> <buy|sell> <value>");
                if (!requireShop(sender, PRICE, GovernanceCapability.PRICE, args[2])) return true;
                boolean buySide;
                if (args[4].equalsIgnoreCase("buy")) buySide = true;
                else if (args[4].equalsIgnoreCase("sell")) buySide = false;
                else return usage(sender, "Side harus buy atau sell.");
                Double price = parseDouble(args[5]);
                if (price == null) return invalidNumber(sender, args[5]);

                Optional<Shop> shop = plugin.findRuntimeShop(args[2]);
                ShopListing listing = shop.map(value -> value.listings().get(args[3])).orElse(null);
                if (listing == null) {
                    sender.sendMessage("§c[CVE Governance] Listing runtime tidak ditemukan untuk governance guardrail.");
                    return true;
                }
                double before = buySide ? listing.buyPrice() : listing.sellPrice();
                GovernanceService.Result guard = governance.validatePriceChange(sender, args[2], before, price);
                if (!guard.success()) {
                    if (isRoleOnly(sender, PRICE)) {
                        send(sender, approvals.requestPrice(sender, args[2], args[3], buySide, before, price, guard.message()));
                    } else {
                        sender.sendMessage("§c[CVE Governance] " + guard.message());
                    }
                    return true;
                }
                send(sender, service.setPrice(sender.getName(), args[2], args[3], buySide, price));
                return true;
            }
            case "mode" -> {
                if (args.length < 5) return usage(sender, "/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>");
                if (!requireShop(sender, ITEM, GovernanceCapability.ITEM, args[2])) return true;
                ListingMode mode = parseMode(args[4]);
                if (mode == null) return usage(sender, "Mode harus BUY, SELL, atau BUY_SELL.");
                send(sender, service.setMode(sender.getName(), args[2], args[3], mode));
                return true;
            }
            case "slot" -> {
                if (args.length < 5) return usage(sender, "/cve shop slot <shop> <listing> <slot>");
                if (!requireShop(sender, ITEM, GovernanceCapability.ITEM, args[2])) return true;
                Integer slot = parseInt(args[4]);
                if (slot == null) return invalidNumber(sender, args[4]);
                send(sender, service.setSlot(sender.getName(), args[2], args[3], slot));
                return true;
            }
            case "initialstock", "maxstock" -> {
                if (args.length < 5) return usage(sender, "/cve shop " + action + " <shop> <listing> <value>");
                if (!requireShop(sender, STOCK, GovernanceCapability.STOCK_CONFIG, args[2])) return true;
                Integer value = parseInt(args[4]);
                if (value == null) return invalidNumber(sender, args[4]);
                ShopAdminService.Result result = action.equals("initialstock")
                        ? service.setInitialStock(sender.getName(), args[2], args[3], value)
                        : service.setMaxStock(sender.getName(), args[2], args[3], value);
                send(sender, result);
                return true;
            }
            case "stock" -> {
                if (args.length < 6) return usage(sender, "/cve shop stock <shop> <listing> <set|add|remove> <amount>");
                if (!requireShop(sender, STOCK, GovernanceCapability.STOCK_RUNTIME, args[2])) return true;
                ShopAdminService.StockOperation operation;
                try {
                    operation = ShopAdminService.StockOperation.valueOf(args[4].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    return usage(sender, "Operation harus set, add, atau remove.");
                }
                Integer value = parseInt(args[5]);
                if (value == null) return invalidNumber(sender, args[5]);

                if (isRoleOnly(sender, STOCK)) {
                    Optional<GovernanceService.Assignment> assignment = sender instanceof Player player
                            ? governance.assignmentFor(player.getUniqueId()) : Optional.empty();
                    if (assignment.isPresent() && assignment.get().role() != GovernanceRole.ROYAL_TREASURER) {
                        long limit = assignment.get().role() == GovernanceRole.ECONOMY_STAFF
                                ? plugin.getConfig().getLong("governance.limits.economy-staff.max-runtime-stock-delta", 128L)
                                : plugin.getConfig().getLong("governance.limits.economy-manager.max-runtime-stock-delta", 1024L);
                        limit = Math.max(0L, limit);
                        boolean sensitive = operation == ShopAdminService.StockOperation.SET || value > limit;
                        if (sensitive) {
                            String reason = operation == ShopAdminService.StockOperation.SET
                                    ? "Runtime stock SET membutuhkan approval role lebih tinggi."
                                    : "Stock delta " + value + " melewati limit role " + assignment.get().role()
                                    + " sebesar " + limit + " per operasi.";
                            send(sender, approvals.requestStock(sender, args[2], args[3], operation, value, reason));
                            return true;
                        }
                    }
                }
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

    private void sendGovernanceHelp(CommandSender sender) {
        sender.sendMessage("§6[CVE Governance] §fBeta.3 RC2 staff governance + sensitive approval:");
        sender.sendMessage("§e/cve governance status §7| §e/cve governance list §7| §e/cve governance who <player>");
        sender.sendMessage("§e/cve governance approval <status|list|show|approve|reject|cancel>");
        if (governance.canAdminGovernance(sender)) {
            sender.sendMessage("§e/cve governance grant <player> <ECONOMY_STAFF|ECONOMY_MANAGER|ROYAL_TREASURER> <shop|*>");
            sender.sendMessage("§e/cve governance revoke <player> <shop|*|all> §7| §e/cve governance reload");
            sender.sendMessage("§e/cve governance approval reload §7| §e/cve governance approval recover <id> <executed|not-executed> CONFIRM");
        }
    }

    private void sendApprovalHelp(CommandSender sender) {
        sender.sendMessage("§6[CVE Approval] §fSensitive change approval:");
        sender.sendMessage("§e/cve governance approval status §7| §e/cve governance approval list");
        sender.sendMessage("§e/cve governance approval show <id> §7| §e/cve governance approval approve <id>");
        sender.sendMessage("§e/cve governance approval reject <id> [reason] §7| §e/cve governance approval cancel <id>");
        if (governance.canAdminGovernance(sender)) {
            sender.sendMessage("§e/cve governance approval reload");
            sender.sendMessage("§e/cve governance approval recover <id> <executed|not-executed> CONFIRM");
        }
    }

    private void sendShopHelp(CommandSender sender) {
        sender.sendMessage("§6[CVE Shop] §fBeta.3 RC2 management + approval guardrail:");
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

    private boolean requireShop(CommandSender sender, String legacyPermission,
                                GovernanceCapability capability, String shopId) {
        if (governance.authorize(sender, legacyPermission, capability, shopId)) return true;
        String scope = shopId == null ? "assigned shop" : shopId;
        sender.sendMessage("§c[CVE Governance] Akses ditolak. Dibutuhkan permission " + legacyPermission
                + " atau role governance dengan capability " + capability + " pada scope " + scope + ".");
        return false;
    }

    private boolean isRoleOnly(CommandSender sender, String legacyPermission) {
        return !sender.hasPermission(ADMIN) && !sender.hasPermission(legacyPermission)
                && sender instanceof Player player && governance.assignmentFor(player.getUniqueId()).isPresent();
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

    private static boolean hasAdmin(CommandSender sender) {
        return sender.hasPermission(ADMIN);
    }

    private static void send(CommandSender sender, ShopAdminService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Shop] " + result.message());
    }

    private static void send(CommandSender sender, GovernanceService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Governance] " + result.message());
    }

    private static void send(CommandSender sender, GovernanceApprovalService.Result result) {
        sender.sendMessage((result.success() ? "§a" : "§c") + "[CVE Approval] " + result.message());
    }

    private static boolean usage(CommandSender sender, String message) {
        sender.sendMessage("§e[CVE Shop] " + message);
        return true;
    }

    private static boolean governanceUsage(CommandSender sender, String message) {
        sender.sendMessage("§e[CVE Governance] " + message);
        return true;
    }

    private static boolean governanceDenied(CommandSender sender) {
        sender.sendMessage("§c[CVE Governance] Kamu tidak memiliki akses governance yang diperlukan.");
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
            if (hasAdmin(sender)) {
                addIfStarts(result, "reload", input);
                addIfStarts(result, "status", input);
                addIfStarts(result, "doctor", input);
                addIfStarts(result, "safety", input);
            }
            if (hasAnyShopAccess(sender)) addIfStarts(result, "shop", input);
            if (governance.canViewGovernance(sender) || approvals.canView(sender)) addIfStarts(result, "governance", input);
            return result;
        }
        if (args[0].equalsIgnoreCase("shop")) return completeShop(sender, args);
        if (args[0].equalsIgnoreCase("governance")) return completeGovernance(sender, args);
        if (!hasAdmin(sender)) return Collections.emptyList();
        if (args.length == 2 && args[0].equalsIgnoreCase("safety")) return matches(args[1], List.of("status", "unlock"));
        if (args.length == 3 && args[0].equalsIgnoreCase("safety") && args[1].equalsIgnoreCase("unlock")) {
            return matches(args[2], List.of("CONFIRM"));
        }
        return Collections.emptyList();
    }

    private List<String> completeGovernance(CommandSender sender, String[] args) {
        if (!governance.canViewGovernance(sender) && !approvals.canView(sender)) return Collections.emptyList();
        if (args.length == 2) {
            List<String> actions = new ArrayList<>(List.of("status", "list", "who", "approval"));
            if (governance.canAdminGovernance(sender)) {
                actions.add("grant");
                actions.add("revoke");
                actions.add("reload");
            }
            return matches(args[1], actions);
        }
        if (args[1].equalsIgnoreCase("approval")) {
            if (args.length == 3) {
                List<String> actions = new ArrayList<>(List.of("status", "list", "show", "approve", "reject", "cancel"));
                if (governance.canAdminGovernance(sender)) {
                    actions.add("reload");
                    actions.add("recover");
                }
                return matches(args[2], actions);
            }
            if (args.length == 4 && List.of("show", "approve", "reject", "cancel").contains(args[2].toLowerCase(Locale.ROOT))) {
                return matches(args[3], approvals.visibleIds(sender, false));
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("recover") && governance.canAdminGovernance(sender)) {
                return matches(args[3], approvals.visibleIds(sender, true));
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("recover")) {
                return matches(args[4], List.of("executed", "not-executed"));
            }
            if (args.length == 6 && args[2].equalsIgnoreCase("recover")) {
                return matches(args[5], List.of("CONFIRM"));
            }
            return Collections.emptyList();
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("grant") && governance.canAdminGovernance(sender)) {
            return matches(args[3], List.of("ECONOMY_STAFF", "ECONOMY_MANAGER", "ROYAL_TREASURER"));
        }
        if (args.length == 5 && args[1].equalsIgnoreCase("grant") && governance.canAdminGovernance(sender)) {
            List<String> scopes = new ArrayList<>(plugin.runtimeShopIds());
            scopes.add("*");
            return matches(args[4], scopes);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("revoke") && governance.canAdminGovernance(sender)) {
            List<String> scopes = new ArrayList<>(plugin.runtimeShopIds());
            scopes.add("*");
            scopes.add("all");
            return matches(args[3], scopes);
        }
        return Collections.emptyList();
    }

    private List<String> completeShop(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> actions = new ArrayList<>();
            if (can(sender, VIEW, GovernanceCapability.VIEW, null)) { actions.add("list"); actions.add("info"); actions.add("schema"); actions.add("validate"); }
            if (can(sender, CREATE, GovernanceCapability.CREATE, "*")) actions.add("create");
            if (canAnyScoped(sender, DELETE, GovernanceCapability.DELETE)) actions.add("delete");
            if (canAnyScoped(sender, EDIT, GovernanceCapability.EDIT)) { actions.add("name"); actions.add("size"); }
            if (canAnyScoped(sender, BIND, GovernanceCapability.BIND)) actions.add("bind");
            if (canAnyScoped(sender, TOGGLE, GovernanceCapability.TOGGLE)) { actions.add("enable"); actions.add("disable"); }
            if (canAnyScoped(sender, MANAGER, GovernanceCapability.MANAGER)) actions.add("manager");
            if (canAnyScoped(sender, ITEM, GovernanceCapability.ITEM)) { actions.add("additem"); actions.add("removeitem"); actions.add("mode"); actions.add("slot"); }
            if (canAnyScoped(sender, PRICE, GovernanceCapability.PRICE)) actions.add("price");
            if (canAnyScoped(sender, STOCK, GovernanceCapability.STOCK_RUNTIME)) { actions.add("initialstock"); actions.add("maxstock"); actions.add("stock"); }
            return matches(args[1], actions);
        }
        if (args.length >= 3) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && !action.equals("create") && !action.equals("list")
                    && !action.equals("schema") && !action.equals("validate")) {
                return matches(args[2], accessibleShopIds(sender, action));
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

    private List<String> accessibleShopIds(CommandSender sender, String action) {
        GovernanceCapability capability = switch (action) {
            case "info" -> GovernanceCapability.VIEW;
            case "delete" -> GovernanceCapability.DELETE;
            case "name", "size" -> GovernanceCapability.EDIT;
            case "bind" -> GovernanceCapability.BIND;
            case "enable", "disable" -> GovernanceCapability.TOGGLE;
            case "manager" -> GovernanceCapability.MANAGER;
            case "additem", "removeitem", "mode", "slot" -> GovernanceCapability.ITEM;
            case "price" -> GovernanceCapability.PRICE;
            case "initialstock", "maxstock" -> GovernanceCapability.STOCK_CONFIG;
            case "stock" -> GovernanceCapability.STOCK_RUNTIME;
            default -> GovernanceCapability.VIEW;
        };
        String permission = switch (capability) {
            case VIEW -> VIEW;
            case DELETE -> DELETE;
            case EDIT -> EDIT;
            case BIND -> BIND;
            case TOGGLE -> TOGGLE;
            case MANAGER -> MANAGER;
            case ITEM -> ITEM;
            case PRICE -> PRICE;
            case STOCK_CONFIG, STOCK_RUNTIME -> STOCK;
            case CREATE -> CREATE;
        };
        List<String> result = new ArrayList<>();
        for (String shopId : plugin.runtimeShopIds()) {
            if (can(sender, permission, capability, shopId)) result.add(shopId);
        }
        return result;
    }

    private boolean canAnyScoped(CommandSender sender, String permission, GovernanceCapability capability) {
        if (sender.hasPermission(ADMIN) || sender.hasPermission(permission)) return true;
        for (String shopId : plugin.runtimeShopIds()) {
            if (governance.authorize(sender, permission, capability, shopId)) return true;
        }
        return false;
    }

    private boolean can(CommandSender sender, String permission, GovernanceCapability capability, String shopId) {
        return governance.authorize(sender, permission, capability, shopId);
    }

    private boolean hasAnyShopAccess(CommandSender sender) {
        return sender.hasPermission(ADMIN) || sender.hasPermission(VIEW) || sender.hasPermission(CREATE)
                || sender.hasPermission(DELETE) || sender.hasPermission(BIND) || sender.hasPermission(TOGGLE)
                || sender.hasPermission(EDIT) || sender.hasPermission(ITEM) || sender.hasPermission(PRICE)
                || sender.hasPermission(STOCK) || sender.hasPermission(MANAGER) || governance.hasAnyShopAccess(sender);
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
