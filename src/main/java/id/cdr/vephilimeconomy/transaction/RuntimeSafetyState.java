package id.cdr.vephilimeconomy.transaction;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.logging.Logger;

public final class RuntimeSafetyState {
    private static final int SCHEMA_VERSION = 1;

    private final File lockFile;
    private final File tempFile;
    private final File lastEvidenceFile;
    private final File historyFile;
    private final Logger logger;

    private volatile boolean stopped;
    private volatile String reason = "";
    private volatile Instant stoppedAt;
    private volatile UUID transactionId;
    private volatile boolean persistenceHealthy = true;

    public RuntimeSafetyState(File dataFolder, Logger logger) {
        this.lockFile = new File(dataFolder, "safety.lock");
        this.tempFile = new File(dataFolder, "safety.lock.tmp");
        this.lastEvidenceFile = new File(dataFolder, "safety.lock.last");
        this.historyFile = new File(dataFolder, "safety-history.log");
        this.logger = logger;
    }

    public synchronized void load() {
        clearInternal();
        cleanupTempBestEffort();

        if (!lockFile.exists()) {
            return;
        }

        try {
            YamlConfiguration yaml = loadStrict(lockFile);
            int schema = yaml.getInt("meta.schema", -1);
            if (schema != SCHEMA_VERSION) {
                throw new IOException("unsupported schema " + schema);
            }
            if (!yaml.getBoolean("active", false)) {
                throw new IOException("lock file exists but active=true is missing");
            }

            String rawStoppedAt = yaml.getString("stopped-at", "");
            String rawReason = yaml.getString("reason", "");
            String rawTransactionId = yaml.getString("transaction-id", "");
            if (rawStoppedAt.isBlank()) {
                throw new IOException("stopped-at is missing");
            }
            if (rawReason.isBlank()) {
                throw new IOException("reason is missing");
            }

            Instant parsedStoppedAt;
            try {
                parsedStoppedAt = Instant.parse(rawStoppedAt);
            } catch (DateTimeParseException exception) {
                throw new IOException("invalid stopped-at timestamp", exception);
            }

            UUID parsedTransactionId = null;
            if (!rawTransactionId.isBlank()) {
                try {
                    parsedTransactionId = UUID.fromString(rawTransactionId);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("invalid transaction-id", exception);
                }
            }

            stopped = true;
            reason = rawReason;
            stoppedAt = parsedStoppedAt;
            transactionId = parsedTransactionId;
            persistenceHealthy = true;
            logger.severe("Persistent economy safety stop ditemukan. Transaksi tetap dikunci sampai recovery eksplisit dilakukan.");
        } catch (IOException exception) {
            stopped = true;
            reason = "CORRUPT_OR_INVALID_SAFETY_LOCK: " + exception.getMessage();
            stoppedAt = Instant.now();
            transactionId = null;
            persistenceHealthy = true;
            logger.severe("safety.lock ada tetapi tidak dapat dipercaya. Fail-closed diaktifkan: " + exception.getMessage());
        }
    }

    public synchronized boolean trip(UUID transactionId, String reason) {
        if (stopped) {
            return false;
        }

        this.stopped = true;
        this.reason = reason == null || reason.isBlank()
                ? "unspecified critical transaction inconsistency"
                : reason;
        this.stoppedAt = Instant.now();
        this.transactionId = transactionId;

        try {
            persistLock();
            persistenceHealthy = true;
        } catch (IOException exception) {
            persistenceHealthy = false;
            logger.severe("GAGAL MENYIMPAN safety.lock. Safety stop aktif di memory, tetapi tidak dijamin bertahan restart: "
                    + exception.getMessage());
        }
        return true;
    }

    public synchronized UnlockResult unlock(String actor) {
        if (!stopped) {
            return new UnlockResult(false, "Safety stop tidak sedang aktif.");
        }

        String normalizedActor = actor == null || actor.isBlank() ? "unknown" : actor;
        try {
            archiveEvidence(normalizedActor);
            Files.deleteIfExists(lockFile.toPath());
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException exception) {
            return new UnlockResult(false, "Gagal menyimpan bukti recovery / menghapus safety.lock: "
                    + exception.getMessage());
        }

        clearInternal();
        logger.warning("Economy safety stop dibuka secara eksplisit oleh " + normalizedActor
                + ". Pastikan saldo, item, dan stock transaksi kritis sudah diperiksa.");
        return new UnlockResult(true, "Safety stop berhasil dibuka. Riwayat recovery disimpan di safety-history.log.");
    }

    public boolean isStopped() {
        return stopped;
    }

    public String reason() {
        return reason;
    }

    public Instant stoppedAt() {
        return stoppedAt;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public boolean persistenceHealthy() {
        return persistenceHealthy;
    }

    public String shortStatus() {
        if (!stopped) {
            return "OK";
        }
        String prefix = persistenceHealthy ? "STOPPED@" : "STOPPED_UNPERSISTED@";
        return prefix + stoppedAt;
    }

    private void persistLock() throws IOException {
        File parent = lockFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create plugin data folder: " + parent);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA_VERSION);
        yaml.set("active", true);
        yaml.set("stopped-at", stoppedAt.toString());
        yaml.set("transaction-id", transactionId == null ? "" : transactionId.toString());
        yaml.set("reason", reason);
        yaml.save(tempFile);

        YamlConfiguration verified = loadStrict(tempFile);
        if (verified.getInt("meta.schema", -1) != SCHEMA_VERSION
                || !verified.getBoolean("active", false)
                || verified.getString("stopped-at", "").isBlank()
                || verified.getString("reason", "").isBlank()) {
            throw new IOException("temporary safety lock verification failed");
        }

        moveReplace(tempFile, lockFile);
    }

    private void archiveEvidence(String actor) throws IOException {
        File parent = historyFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create safety history directory: " + parent);
        }

        if (lockFile.exists()) {
            Files.copy(lockFile.toPath(), lastEvidenceFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        String line = Instant.now()
                + "\taction=UNLOCK"
                + "\tactor=" + escape(actor)
                + "\tstoppedAt=" + (stoppedAt == null ? "unknown" : stoppedAt)
                + "\ttx=" + (transactionId == null ? "unknown" : transactionId)
                + "\tpersisted=" + persistenceHealthy
                + "\treason=" + escape(reason)
                + System.lineSeparator();
        Files.writeString(historyFile.toPath(), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    private YamlConfiguration loadStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private void cleanupTempBestEffort() {
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException exception) {
            logger.warning("Gagal membersihkan temporary safety lock: " + exception.getMessage());
        }
    }

    private void clearInternal() {
        stopped = false;
        reason = "";
        stoppedAt = null;
        transactionId = null;
        persistenceHealthy = true;
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public record UnlockResult(boolean success, String message) {
    }
}
