package id.cdr.vephilimeconomy.pricing;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Durable sampled market quote state for beta.4.
 *
 * <p>The repository stores only the bounded market multiplier/sample evidence.
 * Base BUY/SELL prices remain owned by shops.yml. A corrupt/unwritable state
 * file blocks dynamic pricing and lets the caller fall back to static base
 * prices instead of inventing a fresh market state.</p>
 */
public final class MarketStateRepository {
    public static final int SCHEMA_VERSION = 1;

    private final File file;
    private final File tempFile;
    private final File backupFile;
    private final Logger logger;
    private final Map<String, Sample> samples = new LinkedHashMap<>();

    private boolean healthy = true;
    private String healthDetail = "OK";

    public MarketStateRepository(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "market-state.yml");
        this.tempFile = new File(dataFolder, "market-state.yml.tmp");
        this.backupFile = new File(dataFolder, "market-state.yml.bak");
        this.logger = logger;
    }

    public synchronized void load() {
        samples.clear();
        healthy = true;
        healthDetail = "OK";

        if (!file.exists()) {
            try {
                persistSnapshot(Map.of(), false);
                logger.info("Initialized durable market-state.yml schema v" + SCHEMA_VERSION + ".");
            } catch (IOException exception) {
                block("initial market-state persistence failed: " + exception.getMessage());
            }
            return;
        }

        try {
            YamlConfiguration yaml = loadStrict(file);
            int schema = yaml.getInt("meta.schema", -1);
            if (schema != SCHEMA_VERSION) {
                throw new IOException("unsupported market-state schema v" + schema
                        + " (expected v" + SCHEMA_VERSION + ")");
            }

            ConfigurationSection shops = yaml.getConfigurationSection("states");
            if (shops != null) {
                for (String shopId : shops.getKeys(false)) {
                    ConfigurationSection listings = shops.getConfigurationSection(shopId);
                    if (listings == null) {
                        continue;
                    }
                    for (String listingId : listings.getKeys(false)) {
                        ConfigurationSection section = listings.getConfigurationSection(listingId);
                        if (section == null) {
                            continue;
                        }
                        Sample sample = parseSample(shopId, listingId, section);
                        samples.put(key(shopId, listingId), sample);
                    }
                }
            }

            Files.deleteIfExists(tempFile.toPath());
            logger.info("Loaded durable market state: samples=" + samples.size() + ".");
        } catch (Exception exception) {
            block("market-state.yml invalid: " + exception.getMessage());
        }
    }

    /**
     * Returns a stable multiplier. A new sample is persisted only when there is
     * no prior state, policy fingerprint changed, or both the cooldown elapsed
     * and stock moved far enough from the last sampled stock.
     */
    public synchronized Resolution resolve(String shopId, String listingId, int currentStock,
                                           double desiredMultiplier, String fingerprint,
                                           long nowMillis, long cooldownMillis,
                                           int minStockChangeToResample) {
        if (!healthy) {
            return Resolution.blocked(healthDetail);
        }
        if (!Double.isFinite(desiredMultiplier) || desiredMultiplier <= 0.0D) {
            return Resolution.blocked("desired multiplier is invalid");
        }

        String key = key(shopId, listingId);
        Sample existing = samples.get(key);
        boolean policyChanged = existing != null && !existing.fingerprint().equals(fingerprint);
        boolean cooldownElapsed = existing == null
                || nowMillis - existing.sampledAtMillis() >= Math.max(0L, cooldownMillis);
        boolean stockMoved = existing == null
                || Math.abs((long) currentStock - existing.sampledStock()) >= Math.max(1, minStockChangeToResample);

        if (existing != null && !policyChanged && (!cooldownElapsed || !stockMoved)) {
            return Resolution.ok(existing.multiplier(), existing.sampledStock(),
                    existing.sampledAtMillis(), false, existing.sampleCount());
        }

        long nextCount = existing == null ? 1L : Math.max(1L, existing.sampleCount() + 1L);
        Sample candidate = new Sample(
                shopId,
                listingId,
                currentStock,
                desiredMultiplier,
                fingerprint,
                nowMillis,
                nextCount
        );

        Map<String, Sample> candidateMap = new LinkedHashMap<>(samples);
        candidateMap.put(key, candidate);
        try {
            persistSnapshot(candidateMap, true);
            samples.clear();
            samples.putAll(candidateMap);
            return Resolution.ok(candidate.multiplier(), candidate.sampledStock(),
                    candidate.sampledAtMillis(), true, candidate.sampleCount());
        } catch (IOException exception) {
            block("market-state persistence failed: " + exception.getMessage());
            return Resolution.blocked(healthDetail);
        }
    }

    public synchronized boolean healthy() {
        return healthy;
    }

    public synchronized int sampleCount() {
        return samples.size();
    }

    public synchronized String healthDetail() {
        return healthDetail;
    }

    public synchronized String shortStatus() {
        return healthy ? "OK(samples=" + samples.size() + ")" : "BLOCKED(" + healthDetail + ")";
    }

    public synchronized Sample sample(String shopId, String listingId) {
        return samples.get(key(shopId, listingId));
    }

    private Sample parseSample(String shopId, String listingId, ConfigurationSection section) throws IOException {
        int sampledStock = section.getInt("sampled-stock", -1);
        double multiplier = section.getDouble("multiplier", Double.NaN);
        String fingerprint = section.getString("fingerprint", "");
        long sampledAt = section.getLong("sampled-at-epoch-ms", -1L);
        long sampleCount = section.getLong("sample-count", 0L);

        if (sampledStock < 0) {
            throw new IOException("negative sampled stock for " + shopId + "/" + listingId);
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0D || multiplier > 10.0D) {
            throw new IOException("invalid multiplier for " + shopId + "/" + listingId);
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IOException("missing policy fingerprint for " + shopId + "/" + listingId);
        }
        if (sampledAt < 0L) {
            throw new IOException("invalid sampled-at timestamp for " + shopId + "/" + listingId);
        }
        if (sampleCount < 1L) {
            throw new IOException("invalid sample-count for " + shopId + "/" + listingId);
        }

        return new Sample(shopId, listingId, sampledStock, multiplier,
                fingerprint, sampledAt, sampleCount);
    }

    private void persistSnapshot(Map<String, Sample> snapshot, boolean createBackup) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("could not create plugin data directory");
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA_VERSION);
        yaml.set("meta.updated-at", Instant.now().toString());
        for (Sample sample : snapshot.values()) {
            String path = "states." + sample.shopId() + "." + sample.listingId();
            yaml.set(path + ".sampled-stock", sample.sampledStock());
            yaml.set(path + ".multiplier", sample.multiplier());
            yaml.set(path + ".fingerprint", sample.fingerprint());
            yaml.set(path + ".sampled-at-epoch-ms", sample.sampledAtMillis());
            yaml.set(path + ".sample-count", sample.sampleCount());
        }
        yaml.save(tempFile);

        YamlConfiguration verified = loadStrict(tempFile);
        if (verified.getInt("meta.schema", -1) != SCHEMA_VERSION) {
            throw new IOException("temporary market-state verification failed");
        }

        if (createBackup && file.exists()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        moveReplace(tempFile, file);
    }

    private static YamlConfiguration loadStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("invalid YAML: " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void block(String detail) {
        healthy = false;
        healthDetail = compact(detail);
        logger.severe("Dynamic market state BLOCKED: " + healthDetail
                + ". Dynamic listings fall back to static base price until state is repaired and runtime reloaded.");
    }

    private static String key(String shopId, String listingId) {
        return shopId + "|" + listingId;
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
    }

    public record Sample(
            String shopId,
            String listingId,
            int sampledStock,
            double multiplier,
            String fingerprint,
            long sampledAtMillis,
            long sampleCount
    ) {
    }

    public record Resolution(
            boolean healthy,
            double multiplier,
            int sampledStock,
            long sampledAtMillis,
            boolean resampled,
            long sampleCount,
            String detail
    ) {
        private static Resolution ok(double multiplier, int stock, long sampledAt,
                                     boolean resampled, long sampleCount) {
            return new Resolution(true, multiplier, stock, sampledAt, resampled, sampleCount, "OK");
        }

        private static Resolution blocked(String detail) {
            return new Resolution(false, 1.0D, 0, 0L, false, 0L, detail);
        }
    }
}
