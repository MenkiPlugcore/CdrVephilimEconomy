package id.cdr.vephilimeconomy.command;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.diagnostic.DoctorService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CveCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "cdrvephilimeconomy.admin";

    private final CdrVephilimEconomy plugin;

    public CveCommand(CdrVephilimEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("§cKamu tidak memiliki permission " + PERMISSION + ".");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6CdrVephilimEconomy §7- §f/cve reload §7| §f/cve status §7| §f/cve doctor §7| §f/cve safety status");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
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

        sender.sendMessage("§cSubcommand tidak dikenal. Gunakan /cve reload, /cve status, /cve doctor, atau /cve safety status.");
        return true;
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            addIfStarts(result, "reload", input);
            addIfStarts(result, "status", input);
            addIfStarts(result, "doctor", input);
            addIfStarts(result, "safety", input);
            return result;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("safety")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            addIfStarts(result, "status", input);
            addIfStarts(result, "unlock", input);
            return result;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("safety")
                && args[1].equalsIgnoreCase("unlock")) {
            return "confirm".startsWith(args[2].toLowerCase(Locale.ROOT))
                    ? List.of("CONFIRM")
                    : Collections.emptyList();
        }

        return Collections.emptyList();
    }

    private static void addIfStarts(List<String> target, String value, String input) {
        if (value.startsWith(input)) {
            target.add(value);
        }
    }
}
