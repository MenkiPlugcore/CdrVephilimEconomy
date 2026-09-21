package id.cdr.vephilimeconomy.pricing;

import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.transaction.TransactionType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Controlled dynamic-pricing quote engine.
 *
 * <p>RC2 keeps market multipliers durable in market-state.yml and only
 * resamples after a configured cooldown plus meaningful stock movement.
 * Base prices remain owned by shops.yml. A broken market-state file blocks
 * dynamic pricing and falls back to static base prices instead of silently
 * creating a new market state.</p>
 */
public final class DynamicPricingService {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final double EPSILON = 0.000001D;

    private final File file;
    private final Logger logger;
    private final Map<String, Policy> policies = new LinkedHashMap<>();
    private final Map<String, DirectionStamp> recentDirections = new ConcurrentHashMap<>();

    private MarketStateRepository marketState;
    private boolean globalEnabled;
    private int configurationWarnings;
    private long quoteCooldownMillis = 30_000L;
    private int minStockChangeToResample = 8;
    private long reversalCooldownMillis = 15_000L;

    public DynamicPricingService(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load(ShopRegistry registry) throws IOException {
        policies.clear();
        recentDirections.clear();
        configurationWarnings = 0;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in pricing.yml: " + exception.getMessage(), exception);
        }

        int schema = yaml.getInt("meta.schema", -1);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("pricing.yml schema tidak didukung: v" + schema
                    + " (expected v" + SCHEMA_VERSION + ")");
        }

        readStabilitySettings(yaml);
        marketState = new MarketStateRepository(file.getParentFile(), logger);
        marketState.load();

        globalEnabled = yaml.getBoolean("enabled", false);
        ConfigurationSection shops = yaml.getConfigurationSection("shops");
        if (shops == null) {
            logger.info("pricing.yml tidak memiliki policy listing; dynamic pricing "
                    + (globalEnabled ? "ON tetapi tidak ada listing aktif." : "OFF."));
            return;
        }

        for (String rawShopId : shops.getKeys(false)) {
            String shopId = normalizeId(rawShopId, "shop");
            ConfigurationSection shopSection = shops.getConfigurationSection(rawShopId);
            if (shopSection == null) {
                continue;
            }

            for (String rawListingId : shopSection.getKeys(false)) {
                String listingId = normalizeId(rawListingId, "listing");
                ConfigurationSection section = shopSection.getConfigurationSection(rawListingId);
                if (section == null) {
                    continue;
                }

                Policy policy = parsePolicy(shopId, listingId, section);
                policies.put(key(shopId, listingId), policy);

                if (registry != null) {
                    Shop shop = registry.findById(shopId).orElse(null);
                    if (shop == null) {
                        warn("Pricing policy mengacu ke shop yang tidak ada: " + shopId + "/" + listingId);
                    } else if (!shop.listings().containsKey(listingId)) {
                        warn("Pricing policy mengacu ke listing yang tidak ada: " + shopId + "/" + listingId);
                    }
                }
            }
        }

        logger.info("Dynamic pricing loaded: enabled=" + globalEnabled
                + ", policies=" + policies.size()
                + ", warnings=" + configurationWarnings
                + ", quoteCooldown=" + quoteCooldownMillis + "ms"
                + ", minStockDelta=" + minStockChangeToResample
                + ", reversalCooldown=" + reversalCooldownMillis + "ms"
                + ", marketState=" + marketState.shortStatus() + ".");
    }

    public PriceQuote quote(Shop shop, ShopListing listing, int currentStock, TransactionType type) {
        double basePrice = type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice();
        Policy policy = policies.get(key(shop.id(), listing.id()));
        double currentRatio = listing.maxStock() <= 0
                ? 0.0D
                : clamp((double) currentStock / (double) listing.maxStock(), 0.0D, 1.0D);

        if (!globalEnabled || policy == null || !policy.enabled()
                || listing.maxStock() <= 0 || basePrice <= 0.0D
                || marketState == null || !marketState.healthy()) {
            return PriceQuote.staticPrice(basePrice, currentRatio);
        }

        double desiredPressure = pressureFor(currentRatio, policy);
        double desiredRawMultiplier = 1.0D + (policy.sensitivity() * desiredPressure);
        double desiredMultiplier = clamp(desiredRawMultiplier, policy.minMultiplier(), policy.maxMultiplier());

        MarketStateRepository.Resolution resolution = marketState.resolve(
                shop.id(),
                listing.id(),
                currentStock,
                desiredMultiplier,
                policyFingerprint(policy, listing),
                System.currentTimeMillis(),
                quoteCooldownMillis,
                minStockChangeToResample
        );
        if (!resolution.healthy()) {
            return PriceQuote.staticPrice(basePrice, currentRatio);
        }

        double multiplier = clamp(resolution.multiplier(), policy.minMultiplier(), policy.maxMultiplier());
        double sampledRatio = clamp((double) resolution.sampledStock() / (double) listing.maxStock(), 0.0D, 1.0D);
        double sampledPressure = pressureFor(sampledRatio, policy);
        double effective = roundCurrency(basePrice * multiplier);
        if (basePrice > 0.0D && effective <= 0.0D) {
            effective = 0.01D;
        }

        return new PriceQuote(basePrice, effective, multiplier, sampledRatio,
                sampledPressure, true);
    }

    /**
     * Blocks a fast BUY -> SELL or SELL -> BUY reversal by the same player on
     * the same dynamic listing. Same-direction demand is not blocked here.
     */
    public ChurnDecision checkChurn(UUID playerId, Shop shop, ShopListing listing,
                                    TransactionType requestedType) {
        if (playerId == null || !isDynamic(shop.id(), listing.id()) || reversalCooldownMillis <= 0L) {
            return ChurnDecision.allow();
        }

        long now = System.currentTimeMillis();
        DirectionStamp previous = recentDirections.get(directionKey(playerId, shop.id(), listing.id()));
        if (previous == null || previous.type() == requestedType) {
            return ChurnDecision.allow();
        }

        long elapsed = Math.max(0L, now - previous.atMillis());
        if (elapsed >= reversalCooldownMillis) {
            return ChurnDecision.allow();
        }

        long remaining = reversalCooldownMillis - elapsed;
        return ChurnDecision.block(remaining,
                "rapid opposite-direction market churn: previous=" + previous.type()
                        + "; requested=" + requestedType
                        + "; remainingMs=" + remaining);
    }

    public void recordSuccessfulTransaction(UUID playerId, Shop shop, ShopListing listing,
                                            TransactionType type) {
        if (playerId == null || !isDynamic(shop.id(), listing.id()) || reversalCooldownMillis <= 0L) {
            return;
        }
        recentDirections.put(directionKey(playerId, shop.id(), listing.id()),
                new DirectionStamp(type, System.currentTimeMillis()));
        if (recentDirections.size() > 10_000) {
            purgeExpiredDirections(System.currentTimeMillis());
        }
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId == null || recentDirections.isEmpty()) {
            return;
        }
        String prefix = playerId.toString() + "|";
        recentDirections.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void clearEphemeralState() {
        recentDirections.clear();
    }

    public boolean globalEnabled() {
        return globalEnabled;
    }

    public int policyCount() {
        return policies.size();
    }

    public int enabledPolicyCount() {
        return (int) policies.values().stream().filter(Policy::enabled).count();
    }

    public int configurationWarningCount() {
        return configurationWarnings;
    }

    public boolean isDynamic(String shopId, String listingId) {
        Policy policy = policies.get(key(shopId, listingId));
        return globalEnabled
                && policy != null
                && policy.enabled()
                && marketState != null
                && marketState.healthy();
    }

    public String shortStatus() {
        String mode;
        if (!globalEnabled) {
            mode = "OFF";
        } else if (marketState == null || !marketState.healthy()) {
            mode = "BLOCKED";
        } else {
            mode = "ON";
        }
        return mode
                + "(" + enabledPolicyCount() + "/" + policies.size() + " policies"
                + ", state=" + (marketState == null ? "UNAVAILABLE" : marketState.shortStatus())
                + ", sample=" + (quoteCooldownMillis / 1000L) + "s"
                + ", reverse=" + (reversalCooldownMillis / 1000L) + "s)";
    }

    public boolean marketStateHealthy() {
        return marketState != null && marketState.healthy();
    }

    public int marketSampleCount() {
        return marketState == null ? 0 : marketState.sampleCount();
    }

    private void readStabilitySettings(ConfigurationSection yaml) {
        int quoteCooldownSeconds = yaml.getInt("stability.quote-cooldown-seconds", 30);
        int minStockDelta = yaml.getInt("stability.min-stock-change-to-resample", 8);
        int reversalCooldownSeconds = yaml.getInt("stability.reversal-cooldown-seconds", 15);

        if (quoteCooldownSeconds < 1 || quoteCooldownSeconds > 3600) {
            throw new IllegalArgumentException("stability.quote-cooldown-seconds harus 1-3600");
        }
        if (minStockDelta < 1 || minStockDelta > 2304) {
            throw new IllegalArgumentException("stability.min-stock-change-to-resample harus 1-2304");
        }
        if (reversalCooldownSeconds < 0 || reversalCooldownSeconds > 600) {
            throw new IllegalArgumentException("stability.reversal-cooldown-seconds harus 0-600");
        }

        quoteCooldownMillis = quoteCooldownSeconds * 1000L;
        minStockChangeToResample = minStockDelta;
        reversalCooldownMillis = reversalCooldownSeconds * 1000L;
    }

    private Policy parsePolicy(String shopId, String listingId, ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", false);
        double target = finite(section.getDouble("target-stock-ratio", 0.50D), "target-stock-ratio", shopId, listingId);
        double sensitivity = finite(section.getDouble("sensitivity", 0.50D), "sensitivity", shopId, listingId);
        double minMultiplier = finite(section.getDouble("min-multiplier", 0.75D), "min-multiplier", shopId, listingId);
        double maxMultiplier = finite(section.getDouble("max-multiplier", 1.50D), "max-multiplier", shopId, listingId);

        if (target < 0.05D || target > 0.95D) {
            throw new IllegalArgumentException("pricing target-stock-ratio harus 0.05-0.95 untuk "
                    + shopId + "/" + listingId);
        }
        if (sensitivity < 0.0D || sensitivity > 5.0D) {
            throw new IllegalArgumentException("pricing sensitivity harus 0-5 untuk "
                    + shopId + "/" + listingId);
        }
        if (minMultiplier <= 0.0D || minMultiplier > 10.0D) {
            throw new IllegalArgumentException("pricing min-multiplier harus >0 dan <=10 untuk "
                    + shopId + "/" + listingId);
        }
        if (maxMultiplier <= 0.0D || maxMultiplier > 10.0D || maxMultiplier < minMultiplier) {
            throw new IllegalArgumentException("pricing max-multiplier tidak valid untuk "
                    + shopId + "/" + listingId);
        }
        if (minMultiplier > 1.0D || maxMultiplier < 1.0D) {
            warn("Policy " + shopId + "/" + listingId
                    + " tidak mengapit multiplier 1.0; harga target-stock tidak sama dengan base price.");
        }

        return new Policy(enabled, target, sensitivity, minMultiplier, maxMultiplier);
    }

    private static double pressureFor(double stockRatio, Policy policy) {
        double pressure;
        if (stockRatio <= policy.targetStockRatio()) {
            pressure = (policy.targetStockRatio() - stockRatio)
                    / Math.max(policy.targetStockRatio(), EPSILON);
        } else {
            pressure = -((stockRatio - policy.targetStockRatio())
                    / Math.max(1.0D - policy.targetStockRatio(), EPSILON));
        }
        return clamp(pressure, -1.0D, 1.0D);
    }

    private double finite(double value, String field, String shopId, String listingId) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("pricing " + field + " harus finite untuk "
                    + shopId + "/" + listingId);
        }
        return value;
    }

    private void warn(String message) {
        configurationWarnings++;
        logger.warning(message);
    }

    private void purgeExpiredDirections(long nowMillis) {
        long retention = Math.max(60_000L, reversalCooldownMillis * 4L);
        recentDirections.entrySet().removeIf(entry -> nowMillis - entry.getValue().atMillis() > retention);
    }

    private static String policyFingerprint(Policy policy, ShopListing listing) {
        String payload = policy.targetStockRatio() + "|"
                + policy.sensitivity() + "|"
                + policy.minMultiplier() + "|"
                + policy.maxMultiplier() + "|"
                + listing.maxStock();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String normalizeId(String raw, String type) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("pricing " + type
                    + " id harus cocok [a-z0-9_-], maksimal 48 karakter: " + raw);
        }
        return normalized;
    }

    private static String key(String shopId, String listingId) {
        return (shopId == null ? "" : shopId.trim().toLowerCase(Locale.ROOT))
                + "|"
                + (listingId == null ? "" : listingId.trim().toLowerCase(Locale.ROOT));
    }

    private static String directionKey(UUID playerId, String shopId, String listingId) {
        return playerId + "|" + key(shopId, listingId);
    }

    private static double roundCurrency(double value) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Policy(
            boolean enabled,
            double targetStockRatio,
            double sensitivity,
            double minMultiplier,
            double maxMultiplier
    ) {
    }

    public record PriceQuote(
            double basePrice,
            double effectivePrice,
            double multiplier,
            double stockRatio,
            double pressure,
            boolean dynamic
    ) {
        private static PriceQuote staticPrice(double price, double stockRatio) {
            return new PriceQuote(price, price, 1.0D, stockRatio, 0.0D, false);
        }
    }

    private record DirectionStamp(TransactionType type, long atMillis) {
    }

    public record ChurnDecision(boolean allowed, long remainingMillis, String detail) {
        private static ChurnDecision allow() {
            return new ChurnDecision(true, 0L, "OK");
        }

        private static ChurnDecision block(long remainingMillis, String detail) {
            return new ChurnDecision(false, Math.max(0L, remainingMillis), detail);
        }
    }
}
