package id.cdr.vephilimeconomy.admin;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class AdminAuditService {
    private final JavaPlugin plugin;
    private final File auditFile;

    public AdminAuditService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.auditFile = new File(plugin.getDataFolder(), "logs/admin-audit.log");
    }

    public synchronized void record(String actor, String action, String detail) throws IOException {
        File parent = auditFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create admin audit directory: " + parent);
        }

        String line = Instant.now()
                + " | actor=" + sanitize(actor)
                + " | action=" + sanitize(action)
                + " | detail=" + sanitize(detail)
                + System.lineSeparator();
        Files.writeString(auditFile.toPath(), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
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

    public File file() {
        return auditFile;
    }

    private static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }
}
