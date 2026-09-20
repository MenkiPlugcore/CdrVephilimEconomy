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

        if (lockFile.exists()) {
            try {
                applySnapshot(readSnapshot(lockFile));
                cleanupTempBestEffort();
                logger.severe("Persistent economy safety stop ditemukan. Transaksi tetap dikunci sampai recovery eksplisit dilakukan.");
                return;
            } catch (IOException primaryFailure) {
                logger.severe("safety.lock tidak valid: " + primaryFailure.getMessage());
                if (tempFile.exists()) {
                    try {
                        SafetySnapshot recovered = readSnapshot(tempFile);
                        moveReplace(tempFile, lockFile);
                        applySnapshot(recovered);
                        logger.severe("safety.lock dipulihkan dari temporary safety snapshot. Economy tetap fail-closed.");
                        return;
                    } catch (IOException tempFailure) {
                        primaryFailure.addSuppressed(tempFailure);
                    }
                }
                activateCorruptLock("CORRUPT_OR_INVALID_SAFETY_LOCK: " + primaryFailure.getMessage());
                return;
            }
        }

        if (tempFile.exists()) {
            try {
                SafetySnapshot recovered = readSnapshot(tempFile);
                moveReplace(tempFile, lockFile);
                applySnapshot(recovered);
                logger.severe("Interrupted safety-lock write dipulihkan dari safety.lock.tmp. Economy tetap fail-closed.");
            } catch (IOException exception) {
                activateCorruptLock("CORRUPT_OR_INCOMPLETE_SAFETY_TEMP: " + exception.getMessage());
            }
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
            logger.severe("GAGAL MENYIMPAN safety.lock. Safety stop aktif di memory, tetapi persistence safety tidak sehat: "
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
            return new UnlockResult(false, "Gagal menyimpan bukti recovery / menghapus safety lock: "
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

        readSnapshot(tempFile);
        moveReplace(tempFile, lockFile);
    }

    private SafetySnapshot readSnapshot(File source) throws IOException {
        YamlConfiguration yaml = loadStrict(source);
        int schema = yaml.getInt("meta.schema", -1);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("unsupported schema " + schema + " in " + source.getName());
        }
        if (!yaml.getBoolean("active", false)) {
            throw new IOException(source.getName() + " exists but active=true is missing");
        }

        String rawStoppedAt = yaml.getString("stopped-at", "");
        String rawReason = yaml.getString("reason", "");
        String rawTransactionId = yaml.getString("transaction-id", "");
        if (rawStoppedAt.isBlank()) {
            throw new IOException("stopped-at is missing in " + source.getName());
        }
        if (rawReason.isBlank()) {
            throw new IOException("reason is missing in " + source.getName());
        }

        Instant parsedStoppedAt;
        try {
            parsedStoppedAt = Instant.parse(rawStoppedAt);
        } catch (DateTimeParseException exception) {
            throw new IOException("invalid stopped-at timestamp in " + source.getName(), exception);
        }

        UUID parsedTransactionId = null;
        if (!rawTransactionId.isBlank()) {
            try {
                parsedTransactionId = UUID.fromString(rawTransactionId);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid transaction-id in " + source.getName(), exception);
            }
        }

        return new SafetySnapshot(parsedStoppedAt, parsedTransactionId, rawReason);
    }

    private void applySnapshot(SafetySnapshot snapshot) {
        stopped = true;
        stoppedAt = snapshot.stoppedAt();
        transactionId = snapshot.transactionId();
        reason = snapshot.reason();
        persistenceHealthy = true;
    }

    private void activateCorruptLock(String detail) {
        stopped = true;
        reason = detail;
        stoppedAt = Instant.now();
        transactionId = null;
        persistenceHealthy = true;
        logger.severe("Safety persistence tidak dapat dipercaya. Fail-closed diaktifkan: " + detail);
    }

    private void archiveEvidence(String actor) throws IOException {
        File parent = historyFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create safety history directory: " + parent);
        }

        File evidence = lockFile.exists() ? lockFile : (tempFile.exists() ? tempFile : null);
        if (evidence != null) {
            Files.copy(evidence.toPath(), lastEvidenceFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
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

    private record SafetySnapshot(Instant stoppedAt, UUID transactionId, String reason) {
    }

    public record UnlockResult(boolean success, String message) {
    }
}
