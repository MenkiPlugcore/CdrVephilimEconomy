package id.cdr.vephilimeconomy.shop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * RC5 user-facing shop storage.
 *
 * <p>Each category is authoritative in {@code plugins/CdrVephilimEconomy/shops/<id>.yml}.
 * A hidden aggregate YAML is generated only as an internal compatibility/validation view for
 * the existing registry and admin journal. It is never intended for manual editing.</p>
 */
public final class ShopDirectoryStorage {
    public static final int SPLIT_SCHEMA = 1;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final String MARKER_NAME = ".shops-split.initialized";

    private final File dataFolder;
    private final Logger logger;
    private final File legacyFile;
    private final File legacyBackup;
    private final File shopsDirectory;
    private final File backupDirectory;
    private final File tempDirectory;
    private final File aggregateFile;
    private final File aggregateTemp;
    private final File initializedMarker;
    private final File splitPendingMarker;

    public ShopDirectoryStorage(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.legacyFile = new File(dataFolder, "shops.yml");
        this.legacyBackup = new File(dataFolder, "shops.yml.legacy.bak");
        this.shopsDirectory = new File(dataFolder, "shops");
        this.backupDirectory = new File(dataFolder, ".shops.admin.bak");
        this.tempDirectory = new File(dataFolder, ".shops.admin.tmp");
        this.aggregateFile = new File(dataFolder, ".shops-aggregate.yml");
        this.aggregateTemp = new File(dataFolder, ".shops-aggregate.tmp");
        this.initializedMarker = new File(dataFolder, MARKER_NAME);
        this.splitPendingMarker = new File(dataFolder, ".shops-split.admin.pending");
    }

    public static boolean isInitialized(File dataFolder) {
        return new File(dataFolder, MARKER_NAME).isFile();
    }

    public File aggregateFile() {
        return aggregateFile;
    }

    public File shopsDirectory() {
        return shopsDirectory;
    }

    public synchronized void ensureInitialized() throws IOException {
        ensureDataFolder();
        recoverInterruptedSplitMutation();

        if (initializedMarker.isFile()) {
            validateInitializedMarker();
            if (!shopsDirectory.isDirectory()) {
                throw new IOException("Split shop marker ada tetapi directory shops/ hilang. Recovery dihentikan fail-closed.");
            }
            rebuildAggregateFromDirectory();
            // onEnable still seeds the bundled legacy resource for backwards compatibility.
            // Once split storage is initialized it is not authoritative and is removed again
            // so operators only see the per-category files they are expected to edit.
            try {
                Files.deleteIfExists(legacyFile.toPath());
            } catch (IOException exception) {
                if (logger != null) {
                    logger.warning("Legacy shops.yml tidak dapat dibersihkan setelah split load: " + exception.getMessage());
                }
            }
            return;
        }

        if (shopsDirectory.exists() && hasAnyEntry(shopsDirectory)) {
            throw new IOException("Directory shops/ sudah berisi data tetapi marker split belum ada. "
                    + "Tidak akan menimpa data yang tidak dapat diverifikasi.");
        }
        if (!legacyFile.isFile()) {
            throw new IOException("shops.yml legacy tidak ditemukan untuk first split migration.");
        }

        ShopsSchemaManager.ensureCurrent(legacyFile, logger);
        YamlConfiguration legacy = loadStrict(legacyFile);
        ConfigurationSection root = legacy.getConfigurationSection("shops");
        if (root == null) {
            throw new IOException("shops.yml tidak memiliki section 'shops'.");
        }

        deleteRecursively(tempDirectory);
        if (!tempDirectory.mkdirs()) {
            throw new IOException("Tidak dapat membuat temporary split shop directory.");
        }

        try {
            writeCategoryDirectory(root, tempDirectory);
            validateDirectory(tempDirectory);

            Files.copy(legacyFile.toPath(), legacyBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (Files.mismatch(legacyFile.toPath(), legacyBackup.toPath()) != -1L) {
                throw new IOException("Backup shops.yml legacy tidak identik dengan source.");
            }

            if (shopsDirectory.exists()) {
                deleteRecursively(shopsDirectory);
            }
            moveDirectory(tempDirectory, shopsDirectory);
            writeInitializedMarker(root.getKeys(false).size());
            rebuildAggregateFromDirectory();

            try {
                Files.deleteIfExists(legacyFile.toPath());
            } catch (IOException exception) {
                if (logger != null) {
                    logger.warning("Split migration sukses tetapi shops.yml legacy tidak dapat dihapus; "
                            + "file tersebut tidak lagi dipakai: " + exception.getMessage());
                }
            }

            if (logger != null) {
                logger.warning("Shop storage migrated to split categories in shops/. Backup legacy: "
                        + legacyBackup.getName() + ".");
            }
        } catch (IOException | RuntimeException exception) {
            deleteRecursively(tempDirectory);
            if (!initializedMarker.isFile() && legacyBackup.isFile() && !legacyFile.isFile()) {
                Files.copy(legacyBackup.toPath(), legacyFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            throw exception;
        }
    }

    /** Rebuilds the hidden aggregate from authoritative category files and validates all shops. */
    public synchronized void rebuildAggregateFromDirectory() throws IOException {
        if (!shopsDirectory.isDirectory()) {
            throw new IOException("Directory shops/ tidak ditemukan.");
        }
        buildAggregate(shopsDirectory, aggregateTemp);
        ShopRegistry candidate = new ShopRegistry();
        candidate.load(aggregateTemp, logger);
        if (candidate.rejectedDefinitionCount() > 0) {
            Files.deleteIfExists(aggregateTemp.toPath());
            throw new IOException("Split shop directory ditolak: " + candidate.rejectedDefinitionCount()
                    + " invalid definition(s).");
        }
        moveReplace(aggregateTemp, aggregateFile);
    }

    /**
     * Replaces the authoritative split directory with the supplied validated aggregate.
     * A persistent pending marker and full directory backup are retained until completeMutation().
     */
    public synchronized void applyAggregate(File sourceAggregate) throws IOException {
        if (splitPendingMarker.isFile()) {
            throw new IOException("Split shop mutation pending sudah ada; restart/recovery diperlukan.");
        }
        if (!shopsDirectory.isDirectory()) {
            throw new IOException("Directory shops/ tidak tersedia untuk mutation.");
        }

        YamlConfiguration aggregate = loadStrict(sourceAggregate);
        if (ShopsSchemaManager.detectSchema(aggregate) != ShopsSchemaManager.CURRENT_SCHEMA) {
            throw new IOException("Internal aggregate bukan schema current.");
        }
        ConfigurationSection root = aggregate.getConfigurationSection("shops");
        if (root == null) {
            throw new IOException("Internal aggregate tidak memiliki section shops.");
        }

        deleteRecursively(tempDirectory);
        if (!tempDirectory.mkdirs()) {
            throw new IOException("Tidak dapat membuat temporary shop directory.");
        }
        writeCategoryDirectory(root, tempDirectory);
        validateDirectory(tempDirectory);

        // Remove the previous inspection backup before creating durable PREPARED evidence.
        // If this cleanup fails, there is no pending marker to confuse next startup.
        deleteRecursively(backupDirectory);
        writeSplitPendingMarker();

        boolean liveMovedToBackup = false;
        try {
            moveDirectory(shopsDirectory, backupDirectory);
            liveMovedToBackup = true;
            moveDirectory(tempDirectory, shopsDirectory);
            rebuildAggregateFromDirectory();
        } catch (IOException exception) {
            if (liveMovedToBackup) {
                try {
                    deleteRecursively(shopsDirectory);
                    if (backupDirectory.isDirectory()) {
                        moveDirectory(backupDirectory, shopsDirectory);
                    }
                    Files.deleteIfExists(splitPendingMarker.toPath());
                    deleteRecursively(tempDirectory);
                    rebuildAggregateFromDirectory();
                } catch (IOException rollbackFailure) {
                    throw new IOException("Split mutation gagal dan rollback juga gagal: "
                            + exception.getMessage() + "; rollback=" + rollbackFailure.getMessage(), rollbackFailure);
                }
            } else {
                try {
                    Files.deleteIfExists(splitPendingMarker.toPath());
                    deleteRecursively(tempDirectory);
                } catch (IOException cleanupFailure) {
                    throw new IOException("Split mutation gagal sebelum backup dan cleanup pending juga gagal: "
                            + exception.getMessage() + "; cleanup=" + cleanupFailure.getMessage(), cleanupFailure);
                }
            }
            throw exception;
        }
    }

    public synchronized void completeMutation() throws IOException {
        Files.deleteIfExists(splitPendingMarker.toPath());
        deleteRecursively(tempDirectory);
        // Keep the latest full directory backup for operator recovery/inspection.
    }

    public synchronized void rollbackMutation() throws IOException {
        if (!backupDirectory.isDirectory()) {
            throw new IOException("Split mutation rollback membutuhkan backup directory tetapi backup tidak tersedia.");
        }
        deleteRecursively(shopsDirectory);
        moveDirectory(backupDirectory, shopsDirectory);
        Files.deleteIfExists(splitPendingMarker.toPath());
        deleteRecursively(tempDirectory);
        rebuildAggregateFromDirectory();
    }

    public synchronized String statusSummary() {
        int categories = 0;
        if (shopsDirectory.isDirectory()) {
            File[] files = shopsDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
            categories = files == null ? 0 : files.length;
        }
        return "split=" + initializedMarker.isFile()
                + ", categories=" + categories
                + ", pending=" + splitPendingMarker.isFile()
                + ", legacyBackup=" + legacyBackup.isFile();
    }

    private void recoverInterruptedSplitMutation() throws IOException {
        if (!splitPendingMarker.isFile()) {
            deleteRecursively(tempDirectory);
            return;
        }
        if (!backupDirectory.isDirectory()) {
            throw new IOException("Interrupted split shop mutation terdeteksi tetapi backup directory hilang. Fail-closed.");
        }
        deleteRecursively(shopsDirectory);
        moveDirectory(backupDirectory, shopsDirectory);
        Files.deleteIfExists(splitPendingMarker.toPath());
        deleteRecursively(tempDirectory);
        if (logger != null) {
            logger.severe("Interrupted split shop mutation dipulihkan dari full directory backup sebelum startup.");
        }
    }

    private void validateDirectory(File directory) throws IOException {
        File candidate = new File(dataFolder, ".shops-split-validate.yml");
        try {
            buildAggregate(directory, candidate);
            ShopRegistry registry = new ShopRegistry();
            registry.load(candidate, logger);
            if (registry.rejectedDefinitionCount() > 0) {
                throw new IOException("Split shop candidate memiliki " + registry.rejectedDefinitionCount()
                        + " rejected definition(s).");
            }
        } finally {
            Files.deleteIfExists(candidate.toPath());
        }
    }

    private void buildAggregate(File directory, File destination) throws IOException {
        List<File> files = categoryFiles(directory);
        YamlConfiguration aggregate = new YamlConfiguration();
        aggregate.set("meta.schema", ShopsSchemaManager.CURRENT_SCHEMA);
        aggregate.set("meta.storage", "split-directory");
        aggregate.set("meta.generated-at", Instant.now().toString());
        ConfigurationSection shops = aggregate.createSection("shops");

        for (File file : files) {
            YamlConfiguration category = loadStrict(file);
            if (category.getInt("meta.schema", -1) != SPLIT_SCHEMA) {
                throw new IOException(file.getName() + " split schema tidak didukung.");
            }
            String id = normalizeId(category.getString("id", ""));
            String fileId = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            if (!id.equals(fileId)) {
                throw new IOException("Category id '" + id + "' tidak cocok filename " + file.getName() + ".");
            }
            if (shops.isConfigurationSection(id)) {
                throw new IOException("Duplicate shop category id: " + id);
            }
            ConfigurationSection target = shops.createSection(id);
            for (String key : category.getKeys(false)) {
                if (key.equals("meta") || key.equals("id")) continue;
                Object value = category.get(key);
                if (value instanceof ConfigurationSection section) {
                    ConfigurationSection nested = target.createSection(key);
                    copySection(section, nested);
                } else {
                    target.set(key, value);
                }
            }
        }

        aggregate.save(destination);
        YamlConfiguration verified = loadStrict(destination);
        if (ShopsSchemaManager.detectSchema(verified) != ShopsSchemaManager.CURRENT_SCHEMA
                || verified.getConfigurationSection("shops") == null) {
            throw new IOException("Generated split aggregate gagal verifikasi.");
        }
    }

    private void writeCategoryDirectory(ConfigurationSection root, File directory) throws IOException {
        for (String rawId : root.getKeys(false)) {
            String id = normalizeId(rawId);
            ConfigurationSection source = root.getConfigurationSection(rawId);
            if (source == null) continue;

            YamlConfiguration category = new YamlConfiguration();
            category.set("meta.schema", SPLIT_SCHEMA);
            category.set("id", id);
            for (String key : source.getKeys(false)) {
                Object value = source.get(key);
                if (value instanceof ConfigurationSection section) {
                    ConfigurationSection nested = category.createSection(key);
                    copySection(section, nested);
                } else {
                    category.set(key, value);
                }
            }
            File target = new File(directory, id + ".yml");
            category.save(target);
            loadStrict(target);
        }
    }

    private void writeInitializedMarker(int categories) throws IOException {
        YamlConfiguration marker = new YamlConfiguration();
        marker.set("meta.schema", 1);
        marker.set("storage", "split-directory");
        marker.set("initialized-at", Instant.now().toString());
        marker.set("initial-categories", categories);
        saveAtomic(marker, initializedMarker);
    }

    private void writeSplitPendingMarker() throws IOException {
        YamlConfiguration marker = new YamlConfiguration();
        marker.set("meta.schema", 1);
        marker.set("state", "PREPARED");
        marker.set("started-at", Instant.now().toString());
        saveAtomic(marker, splitPendingMarker);
    }

    private void validateInitializedMarker() throws IOException {
        YamlConfiguration marker = loadStrict(initializedMarker);
        if (marker.getInt("meta.schema", -1) != 1
                || !"split-directory".equals(marker.getString("storage", ""))) {
            throw new IOException("Split shop initialized marker tidak valid.");
        }
    }

    private void saveAtomic(YamlConfiguration yaml, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        yaml.save(temp);
        loadStrict(temp);
        moveReplace(temp, target);
    }

    private static void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection section) {
                ConfigurationSection nested = target.createSection(key);
                copySection(section, nested);
            } else {
                target.set(key, value);
            }
        }
    }

    private static String normalizeId(String raw) throws IOException {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(normalized).matches()) {
            throw new IOException("Shop id tidak valid untuk split storage: " + raw);
        }
        return normalized;
    }

    private static List<File> categoryFiles(File directory) throws IOException {
        File[] array = directory.listFiles((dir, name) -> name.endsWith(".yml") && !name.startsWith("."));
        if (array == null) {
            throw new IOException("Tidak dapat membaca directory " + directory.getName() + ".");
        }
        List<File> files = new ArrayList<>(List.of(array));
        files.sort(Comparator.comparing(File::getName));
        return files;
    }

    private static YamlConfiguration loadStrict(File file) throws IOException {
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

    private void ensureDataFolder() throws IOException {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Tidak dapat membuat plugin data folder.");
        }
    }

    private static boolean hasAnyEntry(File directory) {
        File[] files = directory.listFiles();
        return files != null && files.length > 0;
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveDirectory(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Tidak dapat membaca directory untuk cleanup: " + file.getName());
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(file.toPath());
    }
}
