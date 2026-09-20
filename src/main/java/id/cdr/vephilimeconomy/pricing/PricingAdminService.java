package id.cdr.vephilimeconomy.pricing;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
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
import java.util.List;
import java.util.Locale;

/**
 * Transactional-ish mutation layer for pricing.yml.
 *
 * The active file is changed only after candidate validation and a durable
 * backup. Runtime reload is then used as the final validator/apply step. If the
 * reload fails the original bytes are restored and the previous runtime is
 * reloaded. Local admin audit is mandatory before any mutation.
 */
public final class PricingAdminService {
    private static final int SCHEMA_VERSION = 1;

    private final CdrVephilimEconomy plugin;
    private final AdminAuditService audit;
    private final File file;
    private final File backup;
    private final File temp;

    public PricingAdminService(CdrVephilimEconomy plugin, AdminAuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
        this.file = new File(plugin.getDataFolder(), "pricing.yml");
        this.backup = new File(plugin.getDataFolder(), "pricing.yml.admin.bak");
        this.temp = new File(plugin.getDataFolder(), "pricing.yml.admin.tmp");
    }

    public Result setGlobal(String actor, boolean enabled) {
        return mutate(actor, "PRICING_GLOBAL", "enabled=" + enabled, yaml -> yaml.set("enabled", enabled));
    }

    public Result setStability(String actor, String rawField, int value) {
        String field = normalizeStabilityField(rawField);
        if (field == null) return Result.fail("Field stability harus quote-cooldown, min-stock-change, atau reversal-cooldown.");

        if (field.equals("quote-cooldown-seconds") && (value < 1 || value > 3600)) {
            return Result.fail("quote-cooldown harus 1-3600 detik.");
        }
        if (field.equals("min-stock-change-to-resample") && (value < 1 || value > 2304)) {
            return Result.fail("min-stock-change harus 1-2304 item.");
        }
        if (field.equals("reversal-cooldown-seconds") && (value < 0 || value > 600)) {
            return Result.fail("reversal-cooldown harus 0-600 detik.");
        }

        return mutate(actor, "PRICING_STABILITY", field + "=" + value,
                yaml -> yaml.set("stability." + field, value));
    }

    public Result setListing(String actor, String rawShopId, String rawListingId,
                             String rawField, String rawValue) {
        String shopId = normalizeId(rawShopId);
        String listingId = normalizeId(rawListingId);
        if (shopId.isBlank() || listingId.isBlank()) return Result.fail("Shop/listing tidak valid.");

        Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
        ShopListing listing = shop == null ? null : shop.listings().get(listingId);
        if (listing == null) return Result.fail("Runtime listing tidak ditemukan: " + shopId + "/" + listingId);

        String field = normalizePolicyField(rawField);
        if (field == null) {
            return Result.fail("Field harus enabled, target, sensitivity, min, atau max.");
        }

        String path = "shops." + shopId + "." + listingId + "." + field;
        if (field.equals("enabled")) {
            Boolean enabled = parseBoolean(rawValue);
            if (enabled == null) return Result.fail("enabled harus true/false atau on/off.");
            return mutate(actor, "PRICING_POLICY", shopId + "/" + listingId + " " + field + "=" + enabled,
                    yaml -> yaml.set(path, enabled));
        }

        Double value = parseFinite(rawValue);
        if (value == null) return Result.fail("Nilai pricing harus angka finite.");
        Result bound = validateSingleField(field, value);
        if (!bound.success()) return bound;

        return mutate(actor, "PRICING_POLICY", shopId + "/" + listingId + " " + field + "=" + value,
                yaml -> yaml.set(path, value));
    }

    public List<String> show(String rawShopId, String rawListingId) {
        String shopId = normalizeId(rawShopId);
        String listingId = normalizeId(rawListingId);
        List<String> lines = new ArrayList<>();
        try {
            YamlConfiguration yaml = loadStrict(file);
            String path = "shops." + shopId + "." + listingId;
            ConfigurationSection section = yaml.getConfigurationSection(path);
            if (section == null) {
                return List.of("§c[CVE Pricing] Policy tidak ditemukan: " + shopId + "/" + listingId);
            }
            lines.add("§6[CVE Pricing] §f" + shopId + "/" + listingId);
            lines.add("§7global=§f" + yaml.getBoolean("enabled", false)
                    + " §7enabled=§f" + section.getBoolean("enabled", false));
            lines.add("§7target=§f" + section.getDouble("target-stock-ratio", 0.50D)
                    + " §7sensitivity=§f" + section.getDouble("sensitivity", 0.50D));
            lines.add("§7min=§f" + section.getDouble("min-multiplier", 0.75D)
                    + " §7max=§f" + section.getDouble("max-multiplier", 1.50D));
            DynamicPricingService pricing = plugin.dynamicPricingService();
            Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
            ShopListing listing = shop == null ? null : shop.listings().get(listingId);
            if (pricing != null && listing != null) {
                int stock = plugin.runtimeStock(shopId, listingId);
                DynamicPricingService.PriceQuote buy = pricing.quote(shop, listing, stock,
                        id.cdr.vephilimeconomy.transaction.TransactionType.BUY);
                DynamicPricingService.PriceQuote sell = pricing.quote(shop, listing, stock,
                        id.cdr.vephilimeconomy.transaction.TransactionType.SELL);
                lines.add("§7stock=§f" + stock + "/" + listing.maxStock()
                        + " §7multiplier=§f" + buy.multiplier());
                lines.add("§7BUY base/effective=§f" + listing.buyPrice() + "/" + buy.effectivePrice()
                        + " §7SELL base/effective=§f" + listing.sellPrice() + "/" + sell.effectivePrice());
            }
            return List.copyOf(lines);
        } catch (IOException exception) {
            return List.of("§c[CVE Pricing] Gagal membaca pricing.yml: " + compact(exception.getMessage()));
        }
    }

    public String status() {
        try {
            YamlConfiguration yaml = loadStrict(file);
            validate(yaml);
            return "schema=v" + yaml.getInt("meta.schema", -1)
                    + ", global=" + yaml.getBoolean("enabled", false)
                    + ", runtime=" + plugin.pricingStatusSummary()
                    + ", audit=" + (audit != null && audit.isWritable() ? "OK" : "BLOCKED");
        } catch (IOException exception) {
            return "INVALID(" + compact(exception.getMessage()) + ")";
        }
    }

    private synchronized Result mutate(String actor, String action, String detail, Mutation mutation) {
        if (audit == null || !audit.isWritable()) {
            return Result.fail("Admin audit tidak writable; mutation pricing ditolak fail-closed.");
        }

        try {
            audit.record(actor, action + "_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Gagal menulis admin audit request: " + exception.getMessage());
        }

        byte[] original;
        try {
            original = Files.readAllBytes(file.toPath());
            YamlConfiguration yaml = loadStrict(file);
            if (yaml.getInt("meta.schema", -1) != SCHEMA_VERSION) {
                return reject(actor, action, detail, "schema pricing bukan v" + SCHEMA_VERSION);
            }

            mutation.apply(yaml);
            yaml.set("meta.updated-at", Instant.now().toString());
            validate(yaml);
            yaml.save(temp);
            validate(loadStrict(temp));

            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            moveReplace(temp, file);
        } catch (Exception exception) {
            deleteTemp();
            return reject(actor, action, detail, "candidate rejected: " + compact(exception.getMessage()));
        }

        CdrVephilimEconomy.ReloadResult reload = plugin.reloadRuntime();
        if (!reload.success()) {
            String rollback;
            try {
                Files.write(file.toPath(), original);
                CdrVephilimEconomy.ReloadResult restored = plugin.reloadRuntime();
                rollback = restored.success() ? "runtime restored" : "RESTORE RELOAD FAILED: " + restored.message();
            } catch (Exception exception) {
                rollback = "RESTORE FILE FAILED: " + exception.getMessage();
            }
            deleteTemp();
            return reject(actor, action, detail, "runtime apply failed: " + reload.message() + "; " + rollback);
        }

        deleteTemp();
        try {
            audit.record(actor, action + "_SUCCESS", detail);
        } catch (IOException exception) {
            plugin.getLogger().warning("Pricing mutation berhasil tetapi SUCCESS audit gagal: " + exception.getMessage());
        }
        return Result.ok("Pricing mutation diterapkan: " + detail + ".");
    }

    private Result reject(String actor, String action, String detail, String reason) {
        try {
            audit.record(actor, action + "_REJECTED", detail + "; reason=" + reason);
        } catch (IOException exception) {
            plugin.getLogger().warning("Gagal menulis pricing rejection audit: " + exception.getMessage());
        }
        return Result.fail(reason);
    }

    private static void validate(YamlConfiguration yaml) throws IOException {
        if (yaml.getInt("meta.schema", -1) != SCHEMA_VERSION) {
            throw new IOException("unsupported pricing schema");
        }

        int quoteCooldown = yaml.getInt("stability.quote-cooldown-seconds", 30);
        int stockDelta = yaml.getInt("stability.min-stock-change-to-resample", 8);
        int reversal = yaml.getInt("stability.reversal-cooldown-seconds", 15);
        if (quoteCooldown < 1 || quoteCooldown > 3600) throw new IOException("quote-cooldown out of range");
        if (stockDelta < 1 || stockDelta > 2304) throw new IOException("min-stock-change out of range");
        if (reversal < 0 || reversal > 600) throw new IOException("reversal-cooldown out of range");

        ConfigurationSection shops = yaml.getConfigurationSection("shops");
        if (shops == null) return;
        for (String shopId : shops.getKeys(false)) {
            ConfigurationSection listings = shops.getConfigurationSection(shopId);
            if (listings == null) continue;
            for (String listingId : listings.getKeys(false)) {
                ConfigurationSection section = listings.getConfigurationSection(listingId);
                if (section == null) continue;
                double target = section.getDouble("target-stock-ratio", 0.50D);
                double sensitivity = section.getDouble("sensitivity", 0.50D);
                double min = section.getDouble("min-multiplier", 0.75D);
                double max = section.getDouble("max-multiplier", 1.50D);
                if (!Double.isFinite(target) || target < 0.05D || target > 0.95D) {
                    throw new IOException("invalid target for " + shopId + "/" + listingId);
                }
                if (!Double.isFinite(sensitivity) || sensitivity < 0.0D || sensitivity > 5.0D) {
                    throw new IOException("invalid sensitivity for " + shopId + "/" + listingId);
                }
                if (!Double.isFinite(min) || min <= 0.0D || min > 10.0D) {
                    throw new IOException("invalid min multiplier for " + shopId + "/" + listingId);
                }
                if (!Double.isFinite(max) || max <= 0.0D || max > 10.0D || max < min) {
                    throw new IOException("invalid max multiplier for " + shopId + "/" + listingId);
                }
            }
        }
    }

    private static Result validateSingleField(String field, double value) {
        if (field.equals("target-stock-ratio") && (value < 0.05D || value > 0.95D)) {
            return Result.fail("target harus 0.05-0.95.");
        }
        if (field.equals("sensitivity") && (value < 0.0D || value > 5.0D)) {
            return Result.fail("sensitivity harus 0-5.");
        }
        if ((field.equals("min-multiplier") || field.equals("max-multiplier"))
                && (value <= 0.0D || value > 10.0D)) {
            return Result.fail("multiplier harus >0 sampai 10.");
        }
        return Result.ok("OK");
    }

    private static String normalizePolicyField(String field) {
        if (field == null) return null;
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "enabled", "enable" -> "enabled";
            case "target", "target-stock-ratio" -> "target-stock-ratio";
            case "sensitivity", "sens" -> "sensitivity";
            case "min", "min-multiplier" -> "min-multiplier";
            case "max", "max-multiplier" -> "max-multiplier";
            default -> null;
        };
    }

    private static String normalizeStabilityField(String field) {
        if (field == null) return null;
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "quote-cooldown", "quote-cooldown-seconds" -> "quote-cooldown-seconds";
            case "min-stock-change", "min-stock-change-to-resample" -> "min-stock-change-to-resample";
            case "reversal-cooldown", "reversal-cooldown-seconds" -> "reversal-cooldown-seconds";
            default -> null;
        };
    }

    public static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static Double parseFinite(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (Exception exception) {
            return null;
        }
    }

    public static Boolean parseBoolean(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on", "enable", "enabled", "yes" -> true;
            case "false", "off", "disable", "disabled", "no" -> false;
            default -> null;
        };
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

    private void deleteTemp() {
        try {
            Files.deleteIfExists(temp.toPath());
        } catch (IOException exception) {
            plugin.getLogger().warning("Gagal membersihkan pricing admin temp: " + exception.getMessage());
        }
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(YamlConfiguration yaml) throws Exception;
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }
}
