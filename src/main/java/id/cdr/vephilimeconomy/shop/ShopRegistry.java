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

public final class ShopRegistry {
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

        for (String shopId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(shopId);
            if (section == null) {
                continue;
            }

            try {
                Shop shop = parseShop(shopId, section);
                shopsById.put(shop.id(), shop);

                if (shop.enabled()) {
                    if (shop.npcId() < 0) {
                        logger.warning("Shop '" + shop.id() + "' enabled tetapi npc-id belum valid. Shop tidak dibind ke NPC.");
                    } else {
                        Shop duplicate = shopsByNpcId.putIfAbsent(shop.npcId(), shop);
                        if (duplicate != null) {
                            throw new IllegalArgumentException("NPC ID " + shop.npcId() + " dipakai oleh shop '" + duplicate.id() + "' dan '" + shop.id() + "'");
                        }
                    }
                }
            } catch (RuntimeException exception) {
                logger.severe("Gagal memuat shop '" + shopId + "': " + exception.getMessage());
            }
        }

        logger.info("Loaded " + shopsById.size() + " shop definition(s), " + shopsByNpcId.size() + " active NPC binding(s).");
    }

    private Shop parseShop(String shopId, ConfigurationSection section) {
        String displayName = section.getString("display-name", shopId);
        int npcId = section.getInt("npc-id", -1);
        int size = section.getInt("size", 27);
        boolean enabled = section.getBoolean("enabled", true);

        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("size harus kelipatan 9 antara 9-54");
        }

        Map<String, ShopListing> listings = new LinkedHashMap<>();
        ConfigurationSection listingRoot = section.getConfigurationSection("listings");
        if (listingRoot != null) {
            for (String listingId : listingRoot.getKeys(false)) {
                ConfigurationSection listingSection = listingRoot.getConfigurationSection(listingId);
                if (listingSection == null) {
                    continue;
                }
                ShopListing listing = parseListing(listingId, listingSection);
                listings.put(listing.id(), listing);
            }
        }

        return new Shop(shopId, displayName, npcId, size, enabled, listings);
    }

    private ShopListing parseListing(String listingId, ConfigurationSection section) {
        String rawMaterial = section.getString("material", "AIR");
        Material material = Material.matchMaterial(rawMaterial);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("material tidak valid untuk listing '" + listingId + "': " + rawMaterial);
        }

        String rawMode = section.getString("mode", "BUY_SELL").toUpperCase(Locale.ROOT);
        ListingMode mode;
        try {
            mode = ListingMode.valueOf(rawMode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mode tidak valid untuk listing '" + listingId + "': " + rawMode);
        }

        int slot = section.getInt("slot", 0);
        double buyPrice = section.getDouble("buy-price", 0.0D);
        double sellPrice = section.getDouble("sell-price", 0.0D);
        int initialStock = section.getInt("initial-stock", 0);
        int maxStock = section.getInt("max-stock", Math.max(initialStock, 0));

        if (mode.canBuy() && buyPrice <= 0) {
            throw new IllegalArgumentException("buy-price harus > 0 untuk listing BUY: " + listingId);
        }
        if (mode.canSell() && sellPrice <= 0) {
            throw new IllegalArgumentException("sell-price harus > 0 untuk listing SELL: " + listingId);
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
}
