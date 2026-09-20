package id.cdr.vephilimeconomy.shop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class ShopRegistry {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");

    private final Map<String, Shop> shopsById = new LinkedHashMap<>();
    private final Map<Integer, Shop> shopsByNpcId = new LinkedHashMap<>();

    public void load(File file, Logger logger) {
        shopsById.clear();
        shopsByNpcId.clear();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("shops");
        if (root == null) {
            logger.warning("shops.yml tidak memiliki section 'shops'. Tidak ada shop yang dimuat.");
            return;
        }

        int rejected = 0;
        for (String rawShopId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawShopId);
            if (section == null) {
                continue;
            }

            try {
                String shopId = normalizeId(rawShopId, "shop");
                Shop shop = parseShop(shopId, section);

                if (shopsById.containsKey(shop.id())) {
                    throw new IllegalArgumentException("shop id duplikat: " + shop.id());
                }

                if (shop.enabled() && shop.npcId() >= 0) {
                    Shop duplicate = shopsByNpcId.get(shop.npcId());
                    if (duplicate != null) {
                        throw new IllegalArgumentException("NPC ID " + shop.npcId()
                                + " sudah dipakai oleh shop '" + duplicate.id() + "'");
                    }
                }

                shopsById.put(shop.id(), shop);
                if (shop.enabled() && shop.npcId() >= 0) {
                    shopsByNpcId.put(shop.npcId(), shop);
                } else if (shop.enabled()) {
                    logger.warning("Shop '" + shop.id() + "' enabled tetapi npc-id belum valid. Shop tidak dibind ke NPC.");
                }
            } catch (RuntimeException exception) {
                rejected++;
                logger.severe("Shop '" + rawShopId + "' ditolak: " + exception.getMessage());
            }
        }

        logger.info("Loaded " + shopsById.size() + " shop definition(s), "
                + shopsByNpcId.size() + " active NPC binding(s), " + rejected + " rejected definition(s).");
    }

    private Shop parseShop(String shopId, ConfigurationSection section) {
        String displayName = section.getString("display-name", shopId);
        int npcId = section.getInt("npc-id", -1);
        int size = section.getInt("size", 27);
        boolean enabled = section.getBoolean("enabled", true);

        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display-name tidak boleh kosong");
        }
        if (npcId < -1) {
            throw new IllegalArgumentException("npc-id harus -1 atau ID Citizens >= 0");
        }
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("size harus kelipatan 9 antara 9-54");
        }

        Map<String, ShopListing> listings = new LinkedHashMap<>();
        ConfigurationSection listingRoot = section.getConfigurationSection("listings");
        if (listingRoot != null) {
            for (String rawListingId : listingRoot.getKeys(false)) {
                ConfigurationSection listingSection = listingRoot.getConfigurationSection(rawListingId);
                if (listingSection == null) {
                    continue;
                }

                String listingId = normalizeId(rawListingId, "listing");
                if (listings.containsKey(listingId)) {
                    throw new IllegalArgumentException("listing id duplikat: " + listingId);
                }

                ShopListing listing = parseListing(listingId, listingSection);
                listings.put(listing.id(), listing);
            }
        }

        return new Shop(shopId, displayName, npcId, size, enabled, listings);
    }

    private ShopListing parseListing(String listingId, ConfigurationSection section) {
        String rawMaterial = section.getString("material", "AIR");
        Material material = Material.matchMaterial(rawMaterial == null ? "AIR" : rawMaterial);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("material tidak valid untuk listing '" + listingId + "': " + rawMaterial);
        }

        String rawMode = section.getString("mode", "BUY_SELL");
        ListingMode mode;
        try {
            mode = ListingMode.valueOf((rawMode == null ? "" : rawMode).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mode tidak valid untuk listing '" + listingId + "': " + rawMode);
        }

        int slot = section.getInt("slot", 0);
        double buyPrice = section.getDouble("buy-price", 0.0D);
        double sellPrice = section.getDouble("sell-price", 0.0D);
        int initialStock = section.getInt("initial-stock", 0);
        int maxStock = section.getInt("max-stock", Math.max(initialStock, 0));

        if (slot < 0) {
            throw new IllegalArgumentException("slot tidak boleh negatif untuk listing '" + listingId + "'");
        }
        if (!Double.isFinite(buyPrice) || !Double.isFinite(sellPrice)) {
            throw new IllegalArgumentException("harga harus angka finite untuk listing '" + listingId + "'");
        }
        if (buyPrice < 0 || sellPrice < 0) {
            throw new IllegalArgumentException("harga tidak boleh negatif untuk listing '" + listingId + "'");
        }
        if (mode.canBuy() && buyPrice <= 0) {
            throw new IllegalArgumentException("buy-price harus > 0 untuk listing BUY: " + listingId);
        }
        if (mode.canSell() && sellPrice <= 0) {
            throw new IllegalArgumentException("sell-price harus > 0 untuk listing SELL: " + listingId);
        }
        if (initialStock < 0 || maxStock < 0 || initialStock > maxStock) {
            throw new IllegalArgumentException("stock bounds tidak valid untuk listing '" + listingId + "'");
        }

        return new ShopListing(listingId, material, slot, mode, buyPrice, sellPrice, initialStock, maxStock);
    }

    public Optional<Shop> findByNpcId(int npcId) {
        return Optional.ofNullable(shopsByNpcId.get(npcId));
    }

    public Optional<Shop> findById(String shopId) {
        return Optional.ofNullable(shopsById.get(shopId));
    }

    public Collection<Shop> all() {
        return Collections.unmodifiableCollection(shopsById.values());
    }

    private static String normalizeId(String raw, String type) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(type + " id harus cocok [a-z0-9_-], maksimal 48 karakter: " + raw);
        }
        return normalized;
    }
}
