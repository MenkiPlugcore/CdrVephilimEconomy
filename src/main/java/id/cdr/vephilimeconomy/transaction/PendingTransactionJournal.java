package id.cdr.vephilimeconomy.transaction;

import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Write-ahead evidence for transactions that may have mutated money/items but
 * have not yet reached a fully persisted commit. Any surviving pending record
 * after a process/server crash is treated as requiring manual reconciliation.
 */
public final class PendingTransactionJournal {
    private static final int SCHEMA_VERSION = 1;

    private final File pendingDirectory;
    private final File recoveryDirectory;
    private final File recoveryLog;
    private final Logger logger;

    public PendingTransactionJournal(File dataFolder, Logger logger) {
        this.pendingDirectory = new File(dataFolder, "pending-transactions");
        this.recoveryDirectory = new File(dataFolder, "transaction-recovery");
        this.recoveryLog = new File(dataFolder, "transaction-recovery.log");
        this.logger = logger;
    }

    public synchronized JournalEntry begin(UUID transactionId, Player player, Shop shop, ShopListing listing,
                                           TransactionType type, int amount, double unitPrice,
                                           double total, int stockBefore) throws IOException {
        ensureDirectory(pendingDirectory);
        JournalEntry entry = new JournalEntry(
                transactionId,
                player.getUniqueId(),
                player.getName(),
                shop.id(),
                listing.id(),
                type,
                amount,
                unitPrice,
                total,
                stockBefore,
                Instant.now(),
                Stage.PREPARED
        );
        persist(entry);
        return entry;
    }

    public synchronized JournalEntry advance(JournalEntry entry, Stage stage) throws IOException {
        JournalEntry updated = entry.withStage(stage);
        persist(updated);
        return updated;
    }

    public synchronized void complete(JournalEntry entry) throws IOException {
        if (entry == null) {
            return;
        }
        Files.deleteIfExists(fileFor(entry.transactionId()).toPath());
        Files.deleteIfExists(tempFor(entry.transactionId()).toPath());
    }

    public synchronized ScanResult scanPending() {
        cleanupTemporaryBestEffort();
        if (!pendingDirectory.isDirectory()) {
            return new ScanResult(0, 0, List.of());
        }

        File[] files = pendingDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return new ScanResult(0, 0, List.of());
        }

        List<PendingEvidence> evidence = new ArrayList<>();
        int corrupt = 0;
        for (File file : files) {
            try {
                YamlConfiguration yaml = loadStrict(file);
                if (yaml.getInt("meta.schema", -1) != SCHEMA_VERSION) {
                    throw new IOException("unsupported schema " + yaml.getInt("meta.schema", -1));
                }
                UUID tx = UUID.fromString(yaml.getString("transaction.id", ""));
                String stage = yaml.getString("transaction.stage", "UNKNOWN");
                String player = yaml.getString("player.name", "unknown");
                String shop = yaml.getString("shop.id", "unknown");
                String listing = yaml.getString("shop.listing", "unknown");
                evidence.add(new PendingEvidence(file.getName(), tx, stage, player, shop, listing, false));
            } catch (Exception exception) {
                corrupt++;
                evidence.add(new PendingEvidence(file.getName(), null, "CORRUPT", "unknown",
                        "unknown", "unknown", true));
                logger.severe("Pending transaction journal tidak valid: " + file.getName()
                        + " - " + exception.getMessage());
            }
        }

        evidence.sort(Comparator.comparing(PendingEvidence::fileName));
        return new ScanResult(files.length, corrupt, List.copyOf(evidence));
    }

    public synchronized ArchiveResult archiveAll(String actor) {
        ScanResult scan = scanPending();
        if (scan.total() == 0) {
            return new ArchiveResult(true, 0, "Tidak ada pending transaction evidence.");
        }

        String normalizedActor = actor == null || actor.isBlank() ? "unknown" : actor;
        try {
            ensureDirectory(recoveryDirectory);
            String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                    .replace(':', '-');
            File[] files = pendingDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) {
                throw new IOException("Could not list pending transaction directory");
            }

            int archived = 0;
            for (File source : files) {
                File target = new File(recoveryDirectory, stamp + "-" + source.getName());
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.delete(source.toPath());
                archived++;
            }

            String line = Instant.now()
                    + "\taction=ARCHIVE_PENDING"
                    + "\tactor=" + escape(normalizedActor)
                    + "\tcount=" + archived
                    + "\tcorrupt=" + scan.corrupt()
                    + System.lineSeparator();
            Files.writeString(recoveryLog.toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            cleanupTemporaryBestEffort();
            return new ArchiveResult(true, archived,
                    "Pending transaction evidence diarsipkan=" + archived + ".");
        } catch (IOException exception) {
            return new ArchiveResult(false, 0,
                    "Gagal mengarsipkan pending transaction evidence: " + exception.getMessage());
        }
    }

    public File pendingDirectory() {
        return pendingDirectory;
    }

    private void persist(JournalEntry entry) throws IOException {
        ensureDirectory(pendingDirectory);
        File temp = tempFor(entry.transactionId());
        File target = fileFor(entry.transactionId());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA_VERSION);
        yaml.set("meta.updated-at", Instant.now().toString());
        yaml.set("transaction.id", entry.transactionId().toString());
        yaml.set("transaction.started-at", entry.startedAt().toString());
        yaml.set("transaction.stage", entry.stage().name());
        yaml.set("transaction.type", entry.type().name());
        yaml.set("transaction.amount", entry.amount());
        yaml.set("transaction.unit-price", entry.unitPrice());
        yaml.set("transaction.total", entry.total());
        yaml.set("transaction.stock-before", entry.stockBefore());
        yaml.set("player.uuid", entry.playerId().toString());
        yaml.set("player.name", entry.playerName());
        yaml.set("shop.id", entry.shopId());
        yaml.set("shop.listing", entry.listingId());
        yaml.save(temp);

        YamlConfiguration verified = loadStrict(temp);
        if (verified.getInt("meta.schema", -1) != SCHEMA_VERSION
                || !entry.transactionId().toString().equals(verified.getString("transaction.id", ""))
                || !entry.stage().name().equals(verified.getString("transaction.stage", ""))) {
            throw new IOException("pending transaction journal verification failed for " + entry.transactionId());
        }
        moveReplace(temp, target);
    }

    private void cleanupTemporaryBestEffort() {
        if (!pendingDirectory.isDirectory()) {
            return;
        }
        File[] temps = pendingDirectory.listFiles((dir, name) -> name.endsWith(".tmp"));
        if (temps == null) {
            return;
        }
        for (File temp : temps) {
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException exception) {
                logger.warning("Gagal membersihkan temporary transaction journal "
                        + temp.getName() + ": " + exception.getMessage());
            }
        }
    }

    private File fileFor(UUID transactionId) {
        return new File(pendingDirectory, transactionId.toString().toLowerCase(Locale.ROOT) + ".yml");
    }

    private File tempFor(UUID transactionId) {
        return new File(pendingDirectory, transactionId.toString().toLowerCase(Locale.ROOT) + ".tmp");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create directory: " + directory);
        }
        if (!directory.isDirectory()) {
            throw new IOException("Path is not a directory: " + directory);
        }
    }

    private static YamlConfiguration loadStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
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
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public enum Stage {
        PREPARED,
        MONEY_WITHDRAWN,
        ITEM_ADDED,
        ITEM_REMOVED,
        MONEY_DEPOSITED,
        STOCK_PERSISTED
    }

    public record JournalEntry(
            UUID transactionId,
            UUID playerId,
            String playerName,
            String shopId,
            String listingId,
            TransactionType type,
            int amount,
            double unitPrice,
            double total,
            int stockBefore,
            Instant startedAt,
            Stage stage
    ) {
        public JournalEntry withStage(Stage nextStage) {
            return new JournalEntry(transactionId, playerId, playerName, shopId, listingId, type,
                    amount, unitPrice, total, stockBefore, startedAt, nextStage);
        }
    }

    public record PendingEvidence(
            String fileName,
            UUID transactionId,
            String stage,
            String playerName,
            String shopId,
            String listingId,
            boolean corrupt
    ) {
    }

    public record ScanResult(int total, int corrupt, List<PendingEvidence> evidence) {
        public UUID firstTransactionId() {
            return evidence.stream()
                    .map(PendingEvidence::transactionId)
                    .filter(id -> id != null)
                    .findFirst()
                    .orElse(null);
        }

        public String summary() {
            if (total == 0) {
                return "pending=0";
            }
            PendingEvidence first = evidence.get(0);
            return "pending=" + total + ", corrupt=" + corrupt
                    + ", first=" + first.fileName() + ", stage=" + first.stage();
        }
    }

    public record ArchiveResult(boolean success, int archived, String message) {
    }
}
