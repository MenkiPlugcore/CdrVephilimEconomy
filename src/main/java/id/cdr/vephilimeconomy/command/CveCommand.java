package id.cdr.vephilimeconomy.command;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
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
            sender.sendMessage("§6CdrVephilimEconomy §7- §f/cve reload §7| §f/cve status");
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

        sender.sendMessage("§cSubcommand tidak dikenal. Gunakan /cve reload atau /cve status.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length != 1) {
            return Collections.emptyList();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if ("reload".startsWith(input)) {
            result.add("reload");
        }
        if ("status".startsWith(input)) {
            result.add("status");
        }
        return result;
    }
}
