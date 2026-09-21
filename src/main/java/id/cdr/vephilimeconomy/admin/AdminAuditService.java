package id.cdr.vephilimeconomy.admin;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.market.MarketRuntimeBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

public final class AdminAuditService {
    private final JavaPlugin plugin;
    private final File auditFile;
    private final HttpClient httpClient;

    public AdminAuditService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.auditFile = new File(plugin.getDataFolder(), "logs/admin-audit.log");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // beta.5 FINAL hardening: the market supply interceptor and automatic
        // expiry lifecycle are plugin-lifetime services, not reloadable runtime
        // listeners. Bootstrap them exactly once after the admin audit sink exists.
        if (plugin instanceof CdrVephilimEconomy economyPlugin) {
            MarketRuntimeBootstrap.start(economyPlugin, this);
        }
    }

    public synchronized void record(String actor, String action, String detail) throws IOException {
        File parent = auditFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create admin audit directory: " + parent);
        }

        String line = Instant.now()
                + " | actor=" + sanitize(actor)
                + " | action=" + sanitize(action)
                + " | detail=" + sanitize(detail);
        Files.writeString(auditFile.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);

        if (discordEnabled() && (discordIncludeRequests() || !action.endsWith("_REQUEST"))) {
            sendDiscordAsync(line);
        }
    }

    public boolean isWritable() {
        try {
            File parent = auditFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            if (auditFile.exists()) {
                return auditFile.isFile() && auditFile.canWrite();
            }
            return parent == null || parent.canWrite();
        } catch (SecurityException exception) {
            plugin.getLogger().warning("Admin audit writability check failed: " + exception.getMessage());
            return false;
        }
    }

    public boolean discordRequested() {
        return plugin.getConfig().getBoolean("admin-audit.discord.enabled", false);
    }

    public boolean discordEnabled() {
        return discordRequested() && discordConfigurationValid();
    }

    public boolean discordIncludeRequests() {
        return plugin.getConfig().getBoolean("admin-audit.discord.include-requests", false);
    }

    public boolean discordConfigurationValid() {
        if (!discordRequested()) {
            return true;
        }
        String webhookUrl = webhookUrl();
        if (webhookUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(webhookUrl);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public File file() {
        return auditFile;
    }

    private String webhookUrl() {
        String value = plugin.getConfig().getString("admin-audit.discord.webhook-url", "");
        return value == null ? "" : value.trim();
    }

    private void sendDiscordAsync(String line) {
        String url = webhookUrl();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"content\":\"" + escapeJson("`CVE Admin` " + line) + "\"}",
                            StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Admin Discord audit webhook URL invalid. Delivery skipped.");
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Admin Discord audit delivery failed: " + throwable.getMessage());
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        plugin.getLogger().warning("Admin Discord audit webhook returned HTTP " + response.statusCode());
                    }
                });
    }

    private static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private static String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
