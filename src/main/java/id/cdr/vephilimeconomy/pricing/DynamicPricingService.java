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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * beta.4 RC1 controlled dynamic-pricing quote engine.
 *
 * <p>The engine is intentionally stateless: the effective quote is derived from
 * the current persisted stock snapshot and a bounded per-listing policy. RC1
 * does not write price state back to shops.yml and therefore cannot corrupt the
 * frozen beta.2 shop-management baseline.</p>
 */
public final class DynamicPricingService {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final double EPSILON = 0.000001D;

    private final File file;
    private final Logger logger;
    private final Map<String, Policy> policies = new LinkedHashMap<>();

    private boolean globalEnabled;
    private int configurationWarnings;

    public DynamicPricingService(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load(ShopRegistry registry) throws IOException {
        policies.clear();
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
                    } else if (shop.listing(listingId) == null) {
                        warn("Pricing policy mengacu ke listing yang tidak ada: " + shopId + "/" + listingId);
                    }
                }
            }
        }

        logger.info("Dynamic pricing loaded: enabled=" + globalEnabled
                + ", policies=" + policies.size()
                + ", warnings=" + configurationWarnings + ".");
    }

    public PriceQuote quote(Shop shop, ShopListing listing, int currentStock, TransactionType type) {
        double basePrice = type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice();
        Policy policy = policies.get(key(shop.id(), listing.id()));

        if (!globalEnabled || policy == null || !policy.enabled()
                || listing.maxStock() <= 0 || basePrice <= 0.0D) {
            return PriceQuote.staticPrice(basePrice, listing.maxStock() <= 0
                    ? 0.0D
                    : clamp((double) currentStock / (double) listing.maxStock(), 0.0D, 1.0D));
        }

        double stockRatio = clamp((double) currentStock / (double) listing.maxStock(), 0.0D, 1.0D);
        double pressure;
        if (stockRatio <= policy.targetStockRatio()) {
            pressure = (policy.targetStockRatio() - stockRatio)
                    / Math.max(policy.targetStockRatio(), EPSILON);
        } else {
            pressure = -((stockRatio - policy.targetStockRatio())
                    / Math.max(1.0D - policy.targetStockRatio(), EPSILON));
        }

        pressure = clamp(pressure, -1.0D, 1.0D);
        double rawMultiplier = 1.0D + (policy.sensitivity() * pressure);
        double multiplier = clamp(rawMultiplier, policy.minMultiplier(), policy.maxMultiplier());
        double effective = roundCurrency(basePrice * multiplier);
        if (basePrice > 0.0D && effective <= 0.0D) {
            effective = 0.01D;
        }

        return new PriceQuote(basePrice, effective, multiplier, stockRatio, pressure, true);
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
        return globalEnabled && policy != null && policy.enabled();
    }

    public String shortStatus() {
        return (globalEnabled ? "ON" : "OFF")
                + "(" + enabledPolicyCount() + "/" + policies.size() + " policies)";
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
}
