package id.cdr.vephilimeconomy.admin;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.logging.Logger;

public final class AdminMutationJournal {
    private static final int MARKER_SCHEMA = 1;

    private final File pendingFile;
    private final File pendingTempFile;
    private final File lastFile;
    private final File historyFile;
    private final Logger logger;

    private boolean blocked;
    private String blockedReason = "";

    public AdminMutationJournal(File dataFolder, Logger logger) {
        this.pendingFile = new File(dataFolder, "shops.yml.admin.pending");
        this.pendingTempFile = new File(dataFolder, "shops.yml.admin.pending.tmp");
        this.lastFile = new File(dataFolder, "shops.yml.admin.pending.last");
        this.historyFile = new File(dataFolder, "logs/admin-recovery.log");
        this.logger = logger;
    }

    public synchronized RecoveryStatus recover(File liveFile, File backupFile) {
        if (!pendingFile.isFile()) {
            cleanupTemp();
            blocked = false;
            blockedReason = "";
            return new RecoveryStatus(true, false, "no pending admin mutation");
        }

        try {
            YamlConfiguration marker = loadStrict(pendingFile);
            validateMarker(marker);

            if (!liveFile.isFile()) {
                return blockRecovery("Pending admin mutation ditemukan tetapi shops.yml tidak tersedia.");
            }

            String liveHash = sha256(Files.readAllBytes(liveFile.toPath()));
            String originalHash = marker.getString("original-sha256", "");
            String candidateHash = marker.getString("candidate-sha256", "");
            String action = marker.getString("action", "UNKNOWN");
            String actor = marker.getString("actor", "unknown");

            if (liveHash.equals(candidateHash)) {
                archive("RECOVERED_COMMITTED", actor, action,
                        "live hash matches candidate; interrupted mutation considered committed");
                blocked = false;
                blockedReason = "";
                if (logger != null) {
                    logger.warning("Recovered interrupted admin mutation as COMMITTED: action=" + action
                            + ", actor=" + actor + ".");
                }
                return new RecoveryStatus(true, true, "recovered committed admin mutation " + action);
            }

            if (liveHash.equals(originalHash)) {
                archive("RECOVERED_ABORTED", actor, action,
                        "live hash matches original; interrupted mutation considered not committed");
                blocked = false;
                blockedReason = "";
                if (logger != null) {
                    logger.warning("Recovered interrupted admin mutation as ABORTED: action=" + action
                            + ", actor=" + actor + ".");
                }
                return new RecoveryStatus(true, true, "recovered aborted admin mutation " + action);
            }

            String backupHash = backupFile.isFile() ? sha256(Files.readAllBytes(backupFile.toPath())) : "missing";
            return blockRecovery("Pending admin mutation ambigu: hash shops.yml tidak cocok original/candidate. backupHash="
                    + backupHash + ". Mutation admin dikunci sampai investigasi manual.");
        } catch (IOException exception) {
            return blockRecovery("Pending admin mutation tidak dapat direkonsiliasi: " + exception.getMessage());
        }
    }

    public synchronized void begin(String actor, String action, String detail,
                                   byte[] originalBytes, byte[] candidateBytes) throws IOException {
        if (blocked) {
            throw new IOException("Admin mutation journal blocked: " + blockedReason);
        }
        if (pendingFile.exists()) {
            throw new IOException("Pending admin mutation sudah ada. Jalankan recovery/restart dan investigasi sebelum mutation baru.");
        }

        YamlConfiguration marker = new YamlConfiguration();
        marker.set("meta.schema", MARKER_SCHEMA);
        marker.set("state", "PREPARED");
        marker.set("actor", actor == null ? "unknown" : actor);
        marker.set("action", action == null ? "UNKNOWN" : action);
        marker.set("detail", detail == null ? "" : detail);
        marker.set("started-at", Instant.now().toString());
        marker.set("updated-at", Instant.now().toString());
        marker.set("original-sha256", sha256(originalBytes));
        marker.set("candidate-sha256", sha256(candidateBytes));
        saveAtomic(marker, pendingFile);
        validateMarker(loadStrict(pendingFile));
    }

    public synchronized void stage(String state) {
        if (!pendingFile.isFile()) {
            return;
        }
        try {
            YamlConfiguration marker = loadStrict(pendingFile);
            validateMarker(marker);
            marker.set("state", state);
            marker.set("updated-at", Instant.now().toString());
            saveAtomic(marker, pendingFile);
        } catch (IOException exception) {
            if (logger != null) {
                logger.severe("Gagal memperbarui stage admin mutation journal: " + exception.getMessage());
            }
        }
    }

    public synchronized void complete(String outcome) {
        if (!pendingFile.isFile()) {
            return;
        }
        try {
            YamlConfiguration marker = loadStrict(pendingFile);
            validateMarker(marker);
            String actor = marker.getString("actor", "unknown");
            String action = marker.getString("action", "UNKNOWN");
            archive(outcome, actor, action, marker.getString("detail", ""));
            blocked = false;
            blockedReason = "";
        } catch (IOException exception) {
            blocked = true;
            blockedReason = "Gagal menyelesaikan admin mutation journal: " + exception.getMessage();
            if (logger != null) {
                logger.severe(blockedReason);
            }
        }
    }

    public synchronized void block(String reason) {
        blocked = true;
        blockedReason = reason == null || reason.isBlank() ? "unknown admin mutation recovery failure" : reason;
        if (logger != null) {
            logger.severe("Admin shop mutation fail-closed: " + blockedReason);
        }
    }

    public synchronized boolean isBlocked() {
        return blocked || pendingFile.isFile();
    }

    public synchronized String statusSummary() {
        if (blocked) {
            return "BLOCKED reason=" + compact(blockedReason);
        }
        if (pendingFile.isFile()) {
            return "PENDING recovery required";
        }
        return "OK";
    }

    public File pendingFile() {
        return pendingFile;
    }

    private RecoveryStatus blockRecovery(String reason) {
        blocked = true;
        blockedReason = reason;
        if (logger != null) {
            logger.severe("Admin mutation recovery fail-closed: " + reason);
        }
        return new RecoveryStatus(false, true, reason);
    }

    private void archive(String outcome, String actor, String action, String detail) throws IOException {
        File parent = historyFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Tidak dapat membuat directory admin recovery log.");
        }

        String line = Instant.now()
                + " | outcome=" + sanitize(outcome)
                + " | actor=" + sanitize(actor)
                + " | action=" + sanitize(action)
                + " | detail=" + sanitize(detail)
                + System.lineSeparator();
        Files.writeString(historyFile.toPath(), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);

        Files.copy(pendingFile.toPath(), lastFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(pendingFile.toPath());
        cleanupTemp();
    }

    private void validateMarker(YamlConfiguration marker) throws IOException {
        if (marker.getInt("meta.schema", -1) != MARKER_SCHEMA) {
            throw new IOException("admin mutation marker schema tidak valid.");
        }
        if (marker.getString("original-sha256", "").length() != 64
                || marker.getString("candidate-sha256", "").length() != 64) {
            throw new IOException("admin mutation marker hash tidak valid.");
        }
    }

    private void saveAtomic(YamlConfiguration yaml, File target) throws IOException {
        yaml.save(pendingTempFile);
        loadStrict(pendingTempFile);
        try {
            Files.move(pendingTempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(pendingTempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private YamlConfiguration loadStrict(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException(file.getName() + " tidak ditemukan.");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + file.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private void cleanupTemp() {
        try {
            Files.deleteIfExists(pendingTempFile.toPath());
        } catch (IOException exception) {
            if (logger != null) {
                logger.warning("Gagal membersihkan admin mutation temp marker: " + exception.getMessage());
            }
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 tidak tersedia di runtime Java.", exception);
        }
    }

    private static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private static String compact(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        String value = sanitize(input);
        return value.length() <= 180 ? value : value.substring(0, 177) + "...";
    }

    public record RecoveryStatus(boolean healthy, boolean hadPending, String detail) {
    }
}
