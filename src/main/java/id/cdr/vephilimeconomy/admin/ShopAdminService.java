package id.cdr.vephilimeconomy.admin;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.shop.ListingMode;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopDirectoryStorage;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.shop.ShopsSchemaManager;
import id.cdr.vephilimeconomy.storage.StockRepository;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ShopAdminService {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");

    private final CdrVephilimEconomy plugin;
    private final StockRepository stocks;
    private final AdminAuditService audit;
    private final AdminMutationJournal mutationJournal;
    private final ShopDirectoryStorage splitStorage;
    private final File shopsFile;
    private final File backupFile;
    private final File candidateFile;
    private final File writeTempFile;

    public ShopAdminService(CdrVephilimEconomy plugin, StockRepository stocks, AdminAuditService audit) {
        this.plugin = plugin;
        this.stocks = stocks;
        this.audit = audit;
        this.splitStorage = new ShopDirectoryStorage(plugin.getDataFolder(), plugin.getLogger());
        this.shopsFile = splitStorage.aggregateFile();
        this.backupFile = new File(plugin.getDataFolder(), ".shops-aggregate.admin.bak");
        this.candidateFile = new File(plugin.getDataFolder(), ".shops-aggregate.admin.candidate");
        this.writeTempFile = new File(plugin.getDataFolder(), ".shops-aggregate.admin.tmp");
        this.mutationJournal = new AdminMutationJournal(plugin.getDataFolder(), plugin.getLogger());

        try {
            splitStorage.ensureInitialized();
        } catch (IOException exception) {
            mutationJournal.block("Split shop storage initialization gagal: " + exception.getMessage());
            plugin.getLogger().severe("Shop management split storage tidak sehat: " + exception.getMessage());
            return;
        }

        AdminMutationJournal.RecoveryStatus recovery = mutationJournal.recover(shopsFile, backupFile);
        if (!recovery.healthy()) {
            plugin.getLogger().severe("Admin mutation recovery belum sehat: " + recovery.detail());
        } else if (recovery.hadPending()) {
            try {
                audit.record("SYSTEM", "ADMIN_MUTATION_RECOVERY", recovery.detail());
            } catch (IOException exception) {
                plugin.getLogger().warning("Admin mutation recovery selesai tetapi audit recovery gagal ditulis: "
                        + exception.getMessage());
            }
        }

        try {
            splitStorage.rebuildAggregateFromDirectory();
            ShopsSchemaManager.SchemaStatus status = ShopsSchemaManager.inspect(shopsFile);
            if (!status.current()) {
                mutationJournal.block("Internal split aggregate memakai schema legacy v" + status.schema() + ".");
            }
        } catch (IOException exception) {
            mutationJournal.block("Shop management split aggregate initialization gagal: " + exception.getMessage());
            plugin.getLogger().severe("Shop management split aggregate initialization gagal: " + exception.getMessage());
        }
    }

    public Result schemaStatus() {
        try {
            splitStorage.rebuildAggregateFromDirectory();
            ShopsSchemaManager.SchemaStatus status = ShopsSchemaManager.inspect(shopsFile);
            return Result.ok("shop storage=" + splitStorage.statusSummary()
                    + "; internalSchema=v" + status.schema() + "/v" + ShopsSchemaManager.CURRENT_SCHEMA
                    + (status.current() ? " CURRENT" : " LEGACY")
                    + "; adminMutation=" + mutationJournal.statusSummary() + ".");
        } catch (IOException exception) {
            return Result.fail("Schema check gagal: " + exception.getMessage()
                    + "; storage=" + splitStorage.statusSummary()
                    + "; adminMutation=" + mutationJournal.statusSummary() + ".");
        }
    }

    public Result validateConfig() {
        if (mutationJournal.isBlocked()) {
            return Result.fail("Admin mutation recovery belum sehat: " + mutationJournal.statusSummary());
        }
        try {
            splitStorage.rebuildAggregateFromDirectory();
            ShopsSchemaManager.SchemaStatus schema = ShopsSchemaManager.inspect(shopsFile);
            ShopRegistry candidate = new ShopRegistry();
            candidate.load(shopsFile, plugin.getLogger());
            if (candidate.rejectedDefinitionCount() > 0) {
                return Result.fail("Split shop config ditolak: rejectedDefinitions=" + candidate.rejectedDefinitionCount() + ".");
            }
            return Result.ok("Valid: schema=v" + schema.schema() + ", shops=" + candidate.shopCount()
                    + ", listings=" + candidate.listingCount() + ", npcBindings=" + candidate.activeBindingCount()
                    + ", warnings=" + candidate.configurationWarningCount()
                    + ", storage=" + splitStorage.statusSummary()
                    + ", adminMutation=" + mutationJournal.statusSummary() + ".");
        } catch (IOException exception) {
            return Result.fail("Validation gagal: " + exception.getMessage());
        }
    }

    public Result createShop(String actor, String rawId, String displayName, int size) {
        String id;
        try {
            id = normalizeId(rawId, "shop");
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = id;
        }
        if (size < 9 || size > 54 || size % 9 != 0) {
            return Result.fail("Size harus kelipatan 9 antara 9-54.");
        }

        String finalDisplayName = displayName;
        return mutate(actor, "SHOP_CREATE", "shop=" + id + "; size=" + size, yaml -> {
            String path = "shops." + id;
            if (yaml.isConfigurationSection(path)) {
                throw new IllegalArgumentException("Shop '" + id + "' sudah ada.");
            }
            ConfigurationSection section = yaml.createSection(path);
            section.set("display-name", finalDisplayName);
            section.set("npc-id", -1);
            section.set("size", size);
            section.set("enabled", false);
            section.set("manager", "");
            section.createSection("listings");
        });
    }

    public Result deleteShop(String actor, String rawId) {
        String id;
        try {
            id = normalizeId(rawId, "shop");
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        return mutate(actor, "SHOP_DELETE", "shop=" + id, yaml -> {
            String path = "shops." + id;
            if (!yaml.isConfigurationSection(path)) {
                throw new IllegalArgumentException("Shop '" + id + "' tidak ditemukan.");
            }
            yaml.set(path, null);
        });
    }

    public Result setDisplayName(String actor, String rawId, String displayName) {
        String id = normalizeOrNull(rawId, "shop");
        if (id == null) {
            return Result.fail("Shop ID tidak valid.");
        }
        String value = displayName == null ? "" : displayName.trim();
        if (value.isBlank()) {
            return Result.fail("Display name tidak boleh kosong.");
        }
        if (value.length() > 128) {
            return Result.fail("Display name maksimal 128 karakter.");
        }
        return mutate(actor, "SHOP_DISPLAY_NAME", "shop=" + id + "; display=" + value,
                yaml -> requireShop(yaml, id).set("display-name", value));
    }

    public Result setSize(String actor, String rawId, int size) {
        if (size < 9 || size > 54 || size % 9 != 0) {
            return Result.fail("Size harus kelipatan 9 antara 9-54.");
        }
        String id = normalizeOrNull(rawId, "shop");
        if (id == null) {
            return Result.fail("Shop ID tidak valid.");
        }
        return mutate(actor, "SHOP_SIZE", "shop=" + id + "; size=" + size,
                yaml -> requireShop(yaml, id).set("size", size));
    }

    public Result bindNpc(String actor, String rawId, int npcId) {
        if (npcId < -1) {
            return Result.fail("NPC ID harus -1 (unbind) atau >= 0.");
        }
        String id = normalizeOrNull(rawId, "shop");
        if (id == null) {
            return Result.fail("Shop ID tidak valid.");
        }
        return mutate(actor, "SHOP_BIND", "shop=" + id + "; npcId=" + npcId,
                yaml -> requireShop(yaml, id).set("npc-id", npcId));
    }

    public Result setEnabled(String actor, String rawId, boolean enabled) {
        String id = normalizeOrNull(rawId, "shop");
        if (id == null) {
            return Result.fail("Shop ID tidak valid.");
        }
        return mutate(actor, enabled ? "SHOP_ENABLE" : "SHOP_DISABLE", "shop=" + id,
                yaml -> requireShop(yaml, id).set("enabled", enabled));
    }

    public Result setManager(String actor, String rawId, String manager) {
        String id = normalizeOrNull(rawId, "shop");
        if (id == null) {
            return Result.fail("Shop ID tidak valid.");
        }
        String value = manager == null || manager.equalsIgnoreCase("none") || manager.equals("-") ? "" : manager.trim();
        if (value.length() > 64) {
            return Result.fail("Manager maksimal 64 karakter.");
        }
        return mutate(actor, "SHOP_MANAGER", "shop=" + id + "; manager=" + (value.isBlank() ? "none" : value),
                yaml -> requireShop(yaml, id).set("manager", value));
    }

    public Result addItem(String actor, String rawShopId, String rawListingId, Material material, int slot,
                          ListingMode mode, double buyPrice, double sellPrice, int initialStock, int maxStock) {
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }
        if (material == null || material.isAir() || !material.isItem()) {
            return Result.fail("Material item tidak valid.");
        }
        if (slot < 0 || initialStock < 0 || maxStock < 0 || initialStock > maxStock) {
            return Result.fail("Slot/stock bounds tidak valid.");
        }
        if (!Double.isFinite(buyPrice) || !Double.isFinite(sellPrice) || buyPrice < 0 || sellPrice < 0) {
            return Result.fail("Harga harus finite dan tidak negatif.");
        }

        String detail = "shop=" + shopId + "; listing=" + listingId + "; material=" + material.name()
                + "; slot=" + slot + "; mode=" + mode + "; buy=" + buyPrice + "; sell=" + sellPrice
                + "; initial=" + initialStock + "; max=" + maxStock;
        return mutate(actor, "LISTING_ADD", detail, yaml -> {
            ConfigurationSection shop = requireShop(yaml, shopId);
            ConfigurationSection listings = shop.getConfigurationSection("listings");
            if (listings == null) {
                listings = shop.createSection("listings");
            }
            if (listings.isConfigurationSection(listingId)) {
                throw new IllegalArgumentException("Listing '" + listingId + "' sudah ada di shop '" + shopId + "'.");
            }
            ConfigurationSection listing = listings.createSection(listingId);
            listing.set("material", material.name());
            listing.set("slot", slot);
            listing.set("mode", mode.name());
            listing.set("buy-price", buyPrice);
            listing.set("sell-price", sellPrice);
            listing.set("initial-stock", initialStock);
            listing.set("max-stock", maxStock);
        });
    }

    public Result removeItem(String actor, String rawShopId, String rawListingId) {
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }
        return mutate(actor, "LISTING_REMOVE", "shop=" + shopId + "; listing=" + listingId, yaml -> {
            ConfigurationSection shop = requireShop(yaml, shopId);
            String path = "listings." + listingId;
            if (!shop.isConfigurationSection(path)) {
                throw new IllegalArgumentException("Listing '" + listingId + "' tidak ditemukan.");
            }
            shop.set(path, null);
        });
    }

    public Result setPrice(String actor, String rawShopId, String rawListingId, boolean buy, double price) {
        if (!Double.isFinite(price) || price < 0) {
            return Result.fail("Harga harus finite dan >= 0.");
        }
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }
        String side = buy ? "buy-price" : "sell-price";
        return mutate(actor, "LISTING_PRICE", "shop=" + shopId + "; listing=" + listingId + "; side=" + side + "; price=" + price,
                yaml -> requireListing(yaml, shopId, listingId).set(side, price));
    }

    public Result setMode(String actor, String rawShopId, String rawListingId, ListingMode mode) {
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }
        return mutate(actor, "LISTING_MODE", "shop=" + shopId + "; listing=" + listingId + "; mode=" + mode,
                yaml -> requireListing(yaml, shopId, listingId).set("mode", mode.name()));
    }

    public Result setSlot(String actor, String rawShopId, String rawListingId, int slot) {
        if (slot < 0) {
            return Result.fail("Slot tidak boleh negatif.");
        }
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }
        return mutate(actor, "LISTING_SLOT", "shop=" + shopId + "; listing=" + listingId + "; slot=" + slot,
                yaml -> requireListing(yaml, shopId, listingId).set("slot", slot));
    }

    public Result setInitialStock(String actor, String rawShopId, String rawListingId, int value) {
        if (value < 0) {
            return Result.fail("initial-stock tidak boleh negatif.");
        }
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }
        return mutate(actor, "LISTING_INITIAL_STOCK", "shop=" + shopId + "; listing=" + listingId + "; value=" + value,
                yaml -> requireListing(yaml, shopId, listingId).set("initial-stock", value));
    }

    public Result setMaxStock(String actor, String rawShopId, String rawListingId, int value) {
        if (value < 0) {
            return Result.fail("max-stock tidak boleh negatif.");
        }
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }
        return mutate(actor, "LISTING_MAX_STOCK", "shop=" + shopId + "; listing=" + listingId + "; value=" + value,
                yaml -> requireListing(yaml, shopId, listingId).set("max-stock", value));
    }

    public synchronized Result changeRuntimeStock(String actor, String rawShopId, String rawListingId,
                                                  StockOperation operation, int value) {
        if (mutationJournal.isBlocked()) {
            return Result.fail("Stock admin diblokir karena admin mutation recovery belum sehat: "
                    + mutationJournal.statusSummary());
        }
        if (plugin.isEconomySafetyStopped()) {
            return Result.fail("Stock admin diblokir saat economy safety stop aktif. Selesaikan recovery terlebih dahulu.");
        }
        if (value < 0) {
            return Result.fail("Jumlah stock tidak boleh negatif.");
        }
        String shopId = normalizeOrNull(rawShopId, "shop");
        String listingId = normalizeOrNull(rawListingId, "listing");
        if (shopId == null || listingId == null) {
            return Result.fail("Shop/listing ID tidak valid.");
        }

        Optional<Shop> shopOptional = plugin.findRuntimeShop(shopId);
        if (shopOptional.isEmpty()) {
            return Result.fail("Shop runtime '" + shopId + "' tidak ditemukan.");
        }
        Shop shop = shopOptional.get();
        ShopListing listing = shop.listings().get(listingId);
        if (listing == null) {
            return Result.fail("Listing runtime '" + listingId + "' tidak ditemukan.");
        }

        int before = stocks.getStock(shopId, listingId);
        long candidate = switch (operation) {
            case SET -> value;
            case ADD -> (long) before + value;
            case REMOVE -> (long) before - value;
        };
        if (candidate < 0 || candidate > listing.maxStock()) {
            return Result.fail("Stock hasil harus 0-" + listing.maxStock() + ". Current=" + before + ", requested=" + candidate + ".");
        }

        String detail = "shop=" + shopId + "; listing=" + listingId + "; operation=" + operation
                + "; before=" + before + "; after=" + candidate;
        try {
            audit.record(actor, "STOCK_CHANGE_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Perubahan dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
        }

        try {
            stocks.setStock(shopId, listing, (int) candidate);
        } catch (IOException | RuntimeException exception) {
            tryRecordFailure(actor, "STOCK_CHANGE_FAILED", detail + "; error=" + exception.getMessage());
            return Result.fail("Gagal menyimpan stock: " + exception.getMessage());
        }

        try {
            audit.record(actor, "STOCK_CHANGE_SUCCESS", detail);
        } catch (IOException exception) {
            plugin.getLogger().severe("Stock berubah tetapi success admin audit gagal ditulis: " + exception.getMessage());
            return Result.ok("Stock berhasil diubah " + before + " -> " + candidate + ", tetapi success-audit gagal ditulis; cek console.");
        }
        return Result.ok("Stock berhasil diubah " + before + " -> " + candidate + ".");
    }

    private synchronized Result mutate(String actor, String action, String detail, YamlMutation mutation) {
        if (mutationJournal.isBlocked()) {
            return Result.fail("Mutation diblokir karena admin mutation recovery belum sehat: "
                    + mutationJournal.statusSummary());
        }

        try {
            splitStorage.rebuildAggregateFromDirectory();
            ShopsSchemaManager.SchemaStatus status = ShopsSchemaManager.inspect(shopsFile);
            if (!status.current()) {
                return Result.fail("Internal split aggregate masih schema legacy v" + status.schema() + ".");
            }
        } catch (IOException exception) {
            return Result.fail("Mutation diblokir karena split shop storage tidak sehat: " + exception.getMessage());
        }

        try {
            audit.record(actor, action + "_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Perubahan dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
        }

        byte[] original;
        YamlConfiguration yaml;
        try {
            original = Files.readAllBytes(shopsFile.toPath());
            yaml = loadStrict(shopsFile);
            mutation.apply(yaml);
            ShopsSchemaManager.stampMutation(yaml);
            validateCandidate(yaml);
        } catch (IOException | RuntimeException exception) {
            tryRecordFailure(actor, action + "_REJECTED", detail + "; error=" + exception.getMessage());
            cleanupTemps();
            return Result.fail(exception.getMessage());
        }

        try {
            Files.copy(shopsFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            yaml.save(writeTempFile);
            loadStrict(writeTempFile);
            byte[] candidateBytes = Files.readAllBytes(writeTempFile.toPath());
            mutationJournal.begin(actor, action, detail, original, candidateBytes);
            moveReplace(writeTempFile, shopsFile);
            splitStorage.applyAggregate(shopsFile);
            mutationJournal.stage("SPLIT_LIVE_REPLACED");
        } catch (IOException exception) {
            boolean recovered = false;
            String recovery = "";
            try {
                Files.write(shopsFile.toPath(), original);
                splitStorage.rebuildAggregateFromDirectory();
                recovered = true;
                recovery = "split runtime source tetap/berhasil dipulihkan";
            } catch (IOException recoveryFailure) {
                recovery = "split recovery gagal: " + recoveryFailure.getMessage();
            }
            if (recovered) {
                mutationJournal.complete("ROLLED_BACK_WRITE_FAILURE");
            } else {
                mutationJournal.block("Gagal menulis split shop mutation " + action + ": "
                        + exception.getMessage() + "; " + recovery);
            }
            cleanupTemps();
            tryRecordFailure(actor, action + "_FAILED", detail + "; writeError=" + exception.getMessage()
                    + "; recovery=" + recovery);
            return Result.fail("Gagal menulis split shop secara atomic: " + exception.getMessage()
                    + "; " + recovery + ".");
        }

        CdrVephilimEconomy.ReloadResult reload = plugin.reloadRuntime();
        if (!reload.success()) {
            String rollbackMessage;
            boolean rollbackHealthy = false;
            try {
                splitStorage.rollbackMutation();
                Files.write(shopsFile.toPath(), original);
                splitStorage.rebuildAggregateFromDirectory();
                CdrVephilimEconomy.ReloadResult rollbackReload = plugin.reloadRuntime();
                rollbackHealthy = rollbackReload.success();
                rollbackMessage = rollbackHealthy
                        ? "split category files dan runtime lama dipulihkan"
                        : "split rollback berhasil tetapi reload rollback gagal: " + rollbackReload.message();
            } catch (IOException exception) {
                rollbackMessage = "split rollback gagal: " + exception.getMessage();
            }

            if (rollbackHealthy) {
                mutationJournal.complete("ROLLED_BACK");
            } else {
                mutationJournal.block("Mutation " + action + " gagal dan rollback runtime tidak dapat dibuktikan sehat: "
                        + rollbackMessage);
            }

            tryRecordFailure(actor, action + "_FAILED", detail + "; reloadError=" + reload.message() + "; " + rollbackMessage);
            cleanupTemps();
            return Result.fail("Perubahan gagal diterapkan: " + reload.message() + "; " + rollbackMessage + ".");
        }

        mutationJournal.stage("RUNTIME_RELOADED");
        boolean auditWarning = false;
        try {
            audit.record(actor, action + "_SUCCESS", detail);
        } catch (IOException exception) {
            auditWarning = true;
            plugin.getLogger().severe("Admin change sukses tetapi success audit gagal ditulis: " + exception.getMessage());
        }

        try {
            // Clear split pending first. If a crash happens before journal completion, the aggregate
            // journal sees the live hash matching the candidate and safely recovers as COMMITTED.
            splitStorage.completeMutation();
        } catch (IOException exception) {
            mutationJournal.block("Mutation runtime berhasil tetapi split pending marker tidak dapat diselesaikan: "
                    + exception.getMessage());
            cleanupTemps();
            return Result.ok("Perubahan sudah aktif, tetapi split recovery marker gagal diselesaikan. "
                    + "Mutation berikutnya dikunci; restart akan melakukan recovery fail-safe. Cek console.");
        }

        mutationJournal.complete(auditWarning ? "COMMITTED_AUDIT_WARNING" : "COMMITTED");
        cleanupTemps();
        if (mutationJournal.isBlocked()) {
            return Result.ok("Perubahan berhasil diterapkan, tetapi cleanup recovery journal gagal. Mutation admin berikutnya dikunci; cek console.");
        }
        return auditWarning
                ? Result.ok("Perubahan berhasil dan runtime direload, tetapi success-audit gagal ditulis; cek console.")
                : Result.ok("Perubahan berhasil diterapkan ke file kategori dan runtime direload tanpa restart.");
    }

    private void validateCandidate(YamlConfiguration yaml) throws IOException {
        try {
            yaml.save(candidateFile);
            ShopsSchemaManager.SchemaStatus schema = ShopsSchemaManager.inspect(candidateFile);
            if (!schema.current()) {
                throw new IOException("Candidate masih memakai legacy schema v" + schema.schema() + ".");
            }
            ShopRegistry candidate = new ShopRegistry();
            candidate.load(candidateFile, plugin.getLogger());
            if (candidate.rejectedDefinitionCount() > 0) {
                throw new IOException("Candidate ditolak: ada " + candidate.rejectedDefinitionCount() + " definisi invalid.");
            }
        } finally {
            try {
                Files.deleteIfExists(candidateFile.toPath());
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private YamlConfiguration loadStrict(File source) throws IOException {
        if (!source.isFile()) {
            throw new IOException(source.getName() + " tidak ditemukan.");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private static ConfigurationSection requireShop(YamlConfiguration yaml, String shopId) {
        ConfigurationSection section = yaml.getConfigurationSection("shops." + shopId);
        if (section == null) {
            throw new IllegalArgumentException("Shop '" + shopId + "' tidak ditemukan.");
        }
        return section;
    }

    private static ConfigurationSection requireListing(YamlConfiguration yaml, String shopId, String listingId) {
        ConfigurationSection shop = requireShop(yaml, shopId);
        ConfigurationSection listing = shop.getConfigurationSection("listings." + listingId);
        if (listing == null) {
            throw new IllegalArgumentException("Listing '" + listingId + "' tidak ditemukan di shop '" + shopId + "'.");
        }
        return listing;
    }

    private void tryRecordFailure(String actor, String action, String detail) {
        try {
            audit.record(actor, action, detail);
        } catch (IOException auditFailure) {
            plugin.getLogger().severe("Gagal menulis admin audit failure: " + auditFailure.getMessage());
        }
    }

    private void cleanupTemps() {
        try {
            Files.deleteIfExists(candidateFile.toPath());
            Files.deleteIfExists(writeTempFile.toPath());
        } catch (IOException exception) {
            plugin.getLogger().warning("Gagal membersihkan admin temp file: " + exception.getMessage());
        }
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String normalizeOrNull(String raw, String type) {
        try {
            return normalizeId(raw, type);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String normalizeId(String raw, String type) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(type + " id harus cocok [a-z0-9_-], maksimal 48 karakter: " + raw);
        }
        return normalized;
    }

    @FunctionalInterface
    private interface YamlMutation {
        void apply(YamlConfiguration yaml);
    }

    public enum StockOperation {
        SET,
        ADD,
        REMOVE
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message == null || message.isBlank() ? "unknown error" : message);
        }
    }
}
