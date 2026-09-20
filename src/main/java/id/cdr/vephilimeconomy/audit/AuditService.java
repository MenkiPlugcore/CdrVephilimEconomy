package id.cdr.vephilimeconomy.audit;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class AuditService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final boolean localEnabled;
    private final boolean discordEnabled;
    private final String webhookUrl;
    private final HttpClient httpClient;
    private BufferedWriter writer;

    public AuditService(JavaPlugin plugin, boolean localEnabled, boolean discordEnabled, String webhookUrl) throws IOException {
        this.plugin = plugin;
        this.localEnabled = localEnabled;
        this.discordEnabled = discordEnabled && webhookUrl != null && !webhookUrl.isBlank();
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        if (localEnabled) {
            File directory = new File(plugin.getDataFolder(), "logs");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create audit log directory");
            }
            writer = new BufferedWriter(new FileWriter(new File(directory, "audit.log"), StandardCharsets.UTF_8, true));
        }
    }

    public void record(AuditEntry entry) {
        String line = formatLine(entry);

        if (localEnabled) {
            synchronized (this) {
                try {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                } catch (IOException exception) {
                    plugin.getLogger().severe("Failed to write economy audit log: " + exception.getMessage());
                }
            }
        }

        if (discordEnabled) {
            sendDiscordAsync(line);
        }
    }

    private void sendDiscordAsync(String line) {
        String body = "{\"content\":\"" + escapeJson("`CdrVephilimEconomy` " + line) + "\"}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Discord audit webhook URL is invalid. Discord logging skipped.");
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Discord audit delivery failed: " + throwable.getMessage());
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        plugin.getLogger().warning("Discord audit webhook returned HTTP " + response.statusCode());
                    }
                });
    }

    private static String formatLine(AuditEntry entry) {
        return String.join(" | ",
                entry.timestamp().toString(),
                "tx=" + entry.transactionId(),
                "player=" + sanitize(entry.playerName()) + "(" + entry.playerId() + ")",
                "shop=" + sanitize(entry.shopId()),
                "listing=" + sanitize(entry.listingId()),
                "type=" + entry.type(),
                "amount=" + entry.amount(),
                "unit=" + entry.unitPrice(),
                "total=" + entry.total(),
                "stock=" + entry.stockBefore() + "->" + entry.stockAfter(),
                "status=" + sanitize(entry.status()),
                "detail=" + sanitize(entry.detail())
        );
    }

    private static String sanitize(String input) {
        return input == null ? "" : input.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private static String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to close economy audit log: " + exception.getMessage());
            }
            writer = null;
        }
    }
}
