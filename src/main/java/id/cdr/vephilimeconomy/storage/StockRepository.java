package id.cdr.vephilimeconomy.storage;

import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class StockRepository {
    private static final int SCHEMA_VERSION = 1;

    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final File initializedMarker;
    private final Logger logger;
    private final Map<String, Integer> stocks = new HashMap<>();

    public StockRepository(File file, Logger logger) {
        this.file = file;
        this.backupFile = new File(file.getParentFile(), file.getName() + ".bak");
        this.tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        this.initializedMarker = new File(file.getParentFile(), file.getName() + ".initialized");
        this.logger = logger;
    }

    public synchronized void load(ShopRegistry registry) throws IOException {
        stocks.clear();

        boolean snapshotExists = file.exists() || backupFile.exists() || tempFile.exists();
        if (!snapshotExists && initializedMarker.exists()) {
            throw new IOException("Semua snapshot stock hilang setelah storage pernah diinisialisasi. "
                    + "Refusing to reset stock to initial-stock.");
        }

        LoadResult loaded = loadBestSnapshot(registry);
        YamlConfiguration yaml = loaded.yaml();
        boolean changed = loaded.recovered() || yaml.getInt("meta.schema", -1) != SCHEMA_VERSION;

        for (Shop shop : registry.all()) {
            for (ShopListing listing : shop.listings().values()) {
                String key = key(shop.id(), listing.id());
                String yamlPath = path(shop.id(), listing.id());
                Object raw = yaml.get(yamlPath);
                int value;

                if (raw == null) {
                    value = listing.initialStock();
                    changed = true;
                } else if (raw instanceof Number number && isWholeNumber(number)) {
                    long longValue = number.longValue();
                    if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                        logger.warning("Stock " + shop.id() + "/" + listing.id()
                                + " di luar range integer. Menggunakan initial-stock.");
                        value = listing.initialStock();
                        changed = true;
                    } else {
                        int stored = (int) longValue;
                        int clamped = Math.max(0, Math.min(listing.maxStock(), stored));
                        if (clamped != stored) {
                            logger.warning("Stock " + shop.id() + "/" + listing.id()
                                    + " berada di luar bounds dan di-clamp: " + stored + " -> " + clamped);
                            changed = true;
                        }
                        value = clamped;
                    }
                } else {
                    logger.warning("Stock " + shop.id() + "/" + listing.id()
                            + " bukan bilangan bulat valid. Menggunakan initial-stock.");
                    value = listing.initialStock();
                    changed = true;
                }

                stocks.put(key, value);
            }
        }

        if (changed || !file.exists()) {
            persist();
        } else if (!backupFile.exists()) {
            refreshBackupBestEffort();
        }

        ensureInitializedMarker();
        cleanupStaleTemp();
    }

    public synchronized void reconcile(ShopRegistry registry) throws IOException {
        Map<String, Integer> previous = new HashMap<>(stocks);
        Map<String, Integer> next = new HashMap<>();
        int added = 0;
        int removed = 0;
        int clamped = 0;

        for (Shop shop : registry.all()) {
            for (ShopListing listing : shop.listings().values()) {
                String key = key(shop.id(), listing.id());
                Integer existing = previous.get(key);
                int value;
                if (existing == null) {
                    value = listing.initialStock();
                    added++;
                } else {
                    value = Math.max(0, Math.min(listing.maxStock(), existing));
                    if (value != existing) {
                        clamped++;
                    }
                }
                next.put(key, value);
            }
        }

        for (String key : previous.keySet()) {
            if (!next.containsKey(key)) {
                removed++;
            }
        }

        stocks.clear();
        stocks.putAll(next);
        try {
            persist();
            ensureInitializedMarker();
            cleanupStaleTemp();
            logger.info("Runtime stock reconcile selesai: entries=" + stocks.size()
                    + ", added=" + added + ", removed=" + removed + ", clamped=" + clamped + ".");
        } catch (IOException exception) {
            stocks.clear();
            stocks.putAll(previous);
            cleanupStaleTemp();
            throw exception;
        }
    }

    public synchronized int getStock(String shopId, String listingId) {
        return stocks.getOrDefault(key(shopId, listingId), 0);
    }

    public synchronized void setStock(String shopId, ShopListing listing, int newStock) throws IOException {
        if (newStock < 0 || newStock > listing.maxStock()) {
            throw new IllegalArgumentException("Stock out of bounds for " + shopId + "/" + listing.id());
        }

        String key = key(shopId, listing.id());
        Integer previous = stocks.put(key, newStock);
        try {
            persist();
            ensureInitializedMarker();
        } catch (IOException exception) {
            if (previous == null) {
                stocks.remove(key);
            } else {
                stocks.put(key, previous);
            }
            cleanupStaleTemp();
            throw exception;
        }
    }

    public synchronized void flush() throws IOException {
        persist();
        ensureInitializedMarker();
    }

    public synchronized int entryCount() {
        return stocks.size();
    }

    private LoadResult loadBestSnapshot(ShopRegistry registry) throws IOException {
        if (file.exists()) {
            try {
                YamlConfiguration yaml = loadStrict(file);
                validateStructure(yaml, registry, file);
                return new LoadResult(yaml, false);
            } catch (IOException primary) {
                logger.severe("stock.yml tidak valid: " + primary.getMessage());
                if (backupFile.exists()) {
                    try {
                        YamlConfiguration backup = loadStrict(backupFile);
                        validateStructure(backup, registry, backupFile);
                        restoreMainFrom(backupFile);
                        logger.warning("stock.yml dipulihkan otomatis dari stock.yml.bak.");
                        return new LoadResult(backup, true);
                    } catch (IOException backupFailure) {
                        primary.addSuppressed(backupFailure);
                    }
                }
                throw primary;
            }
        }

        if (backupFile.exists()) {
            YamlConfiguration backup = loadStrict(backupFile);
            validateStructure(backup, registry, backupFile);
            restoreMainFrom(backupFile);
            logger.warning("stock.yml hilang dan dipulihkan dari stock.yml.bak.");
            return new LoadResult(backup, true);
        }

        if (tempFile.exists()) {
            try {
                YamlConfiguration temp = loadStrict(tempFile);
                validateStructure(temp, registry, tempFile);
                restoreMainFrom(tempFile);
                logger.warning("stock.yml dipulihkan dari temporary snapshot karena snapshot utama/backup tidak tersedia.");
                return new LoadResult(temp, true);
            } catch (IOException exception) {
                logger.warning("Temporary stock snapshot invalid dan diabaikan: " + exception.getMessage());
                cleanupStaleTemp();
            }
        }

        logger.info("Belum ada snapshot stock. Menginisialisasi storage baru dari initial-stock.");
        return new LoadResult(new YamlConfiguration(), false);
    }

    private void persist() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create plugin data folder: " + parent);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA_VERSION);
        yaml.set("meta.updated-at", Instant.now().toString());
        for (Map.Entry<String, Integer> entry : stocks.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            yaml.set(path(parts[0], parts[1]), entry.getValue());
        }

        try {
            yaml.save(tempFile);
            YamlConfiguration verified = loadStrict(tempFile);
            verifySnapshot(verified);
            moveReplace(tempFile, file);
            refreshBackupBestEffort();
        } catch (IOException exception) {
            cleanupStaleTemp();
            throw exception;
        }
    }

    private void ensureInitializedMarker() throws IOException {
        File parent = initializedMarker.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create stock marker directory: " + parent);
        }

        String marker = "schema=" + SCHEMA_VERSION + System.lineSeparator()
                + "initialized-at=" + Instant.now() + System.lineSeparator();
        Files.writeString(initializedMarker.toPath(), marker, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void verifySnapshot(YamlConfiguration yaml) throws IOException {
        if (yaml.getInt("meta.schema", -1) != SCHEMA_VERSION) {
            throw new IOException("Temporary stock snapshot schema verification failed");
        }

        for (Map.Entry<String, Integer> entry : stocks.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            Object raw = yaml.get(path(parts[0], parts[1]));
            if (!(raw instanceof Number number) || !isWholeNumber(number)
                    || number.longValue() != entry.getValue()) {
                throw new IOException("Temporary stock snapshot verification failed for " + entry.getKey());
            }
        }
    }

    private void validateStructure(YamlConfiguration yaml, ShopRegistry registry, File source) throws IOException {
        boolean hasConfiguredListings = registry.all().stream().anyMatch(shop -> !shop.listings().isEmpty());
        if (!hasConfiguredListings) {
            return;
        }

        if (yaml.getConfigurationSection("shops") == null) {
            throw new IOException(source.getName() + " tidak memiliki section shops");
        }
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

    private void restoreMainFrom(File source) throws IOException {
        File recovery = new File(file.getParentFile(), file.getName() + ".recover");
        try {
            Files.copy(source.toPath(), recovery.toPath(), StandardCopyOption.REPLACE_EXISTING);
            moveReplace(recovery, file);
        } finally {
            try {
                Files.deleteIfExists(recovery.toPath());
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private void refreshBackupBestEffort() {
        if (!file.exists()) {
            return;
        }

        File backupTemp = new File(file.getParentFile(), backupFile.getName() + ".tmp");
        try {
            Files.copy(file.toPath(), backupTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            loadStrict(backupTemp);
            moveReplace(backupTemp, backupFile);
        } catch (IOException exception) {
            logger.warning("Gagal memperbarui stock backup: " + exception.getMessage());
            try {
                Files.deleteIfExists(backupTemp.toPath());
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private void cleanupStaleTemp() {
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException exception) {
            logger.warning("Gagal membersihkan temporary stock file: " + exception.getMessage());
        }
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isWholeNumber(Number number) {
        double value = number.doubleValue();
        return Double.isFinite(value) && value == Math.rint(value);
    }

    private static String key(String shopId, String listingId) {
        return shopId + "|" + listingId;
    }

    private static String path(String shopId, String listingId) {
        return "shops." + shopId + "." + listingId;
    }

    private record LoadResult(YamlConfiguration yaml, boolean recovered) {
    }
}
