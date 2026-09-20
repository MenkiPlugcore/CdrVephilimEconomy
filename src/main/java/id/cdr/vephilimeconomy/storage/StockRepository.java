package id.cdr.vephilimeconomy.storage;

import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class StockRepository {
    private final File file;
    private final Map<String, Integer> stocks = new HashMap<>();

    public StockRepository(File file) {
        this.file = file;
    }

    public synchronized void load(ShopRegistry registry) throws IOException {
        stocks.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;

        for (Shop shop : registry.all()) {
            for (ShopListing listing : shop.listings().values()) {
                String key = key(shop.id(), listing.id());
                String yamlPath = path(shop.id(), listing.id());
                int value;

                if (yaml.contains(yamlPath)) {
                    value = yaml.getInt(yamlPath, listing.initialStock());
                    int clamped = Math.max(0, Math.min(listing.maxStock(), value));
                    if (clamped != value) {
                        changed = true;
                    }
                    value = clamped;
                } else {
                    value = listing.initialStock();
                    changed = true;
                }

                stocks.put(key, value);
            }
        }

        if (changed || !file.exists()) {
            persist();
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
        } catch (IOException exception) {
            if (previous == null) {
                stocks.remove(key);
            } else {
                stocks.put(key, previous);
            }
            throw exception;
        }
    }

    public synchronized void flush() throws IOException {
        persist();
    }

    private void persist() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create plugin data folder: " + parent);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Integer> entry : stocks.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            yaml.set(path(parts[0], parts[1]), entry.getValue());
        }

        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        yaml.save(temp);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String key(String shopId, String listingId) {
        return shopId + "|" + listingId;
    }

    private static String path(String shopId, String listingId) {
        return "shops." + shopId + "." + listingId;
    }
}
