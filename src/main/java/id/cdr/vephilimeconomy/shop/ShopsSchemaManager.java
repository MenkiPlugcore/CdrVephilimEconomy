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
import java.util.logging.Logger;

public final class ShopsSchemaManager {
    public static final int LEGACY_SCHEMA = 1;
    public static final int CURRENT_SCHEMA = 2;

    private ShopsSchemaManager() {
    }

    public static SchemaStatus inspect(File file) throws IOException {
        YamlConfiguration yaml = loadStrict(file);
        int schema = detectSchema(yaml);
        validateSupported(schema);
        return new SchemaStatus(schema, schema == CURRENT_SCHEMA, false,
                schema == CURRENT_SCHEMA ? "schema current" : "legacy schema requires migration");
    }

    public static SchemaStatus ensureCurrent(File file, Logger logger) throws IOException {
        YamlConfiguration yaml = loadStrict(file);
        int schema = detectSchema(yaml);
        validateSupported(schema);

        if (schema == CURRENT_SCHEMA) {
            return new SchemaStatus(schema, true, false, "schema current");
        }

        if (schema != LEGACY_SCHEMA) {
            throw new IOException("shops.yml schema " + schema + " tidak memiliki migration path ke " + CURRENT_SCHEMA + ".");
        }

        ConfigurationSection shops = yaml.getConfigurationSection("shops");
        if (shops == null) {
            throw new IOException("shops.yml legacy tidak memiliki section 'shops'.");
        }

        for (String shopId : shops.getKeys(false)) {
            ConfigurationSection shop = shops.getConfigurationSection(shopId);
            if (shop == null) {
                continue;
            }
            if (!shop.contains("manager")) {
                shop.set("manager", "");
            }
            if (shop.getConfigurationSection("listings") == null) {
                shop.createSection("listings");
            }
        }

        yaml.set("meta.schema", CURRENT_SCHEMA);
        yaml.set("meta.migrated-from", LEGACY_SCHEMA);
        yaml.set("meta.migrated-at", Instant.now().toString());
        yaml.set("meta.updated-at", Instant.now().toString());

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Tidak dapat membuat data directory untuk schema migration.");
        }

        File backup = new File(parent, file.getName() + ".schema-v" + LEGACY_SCHEMA + ".bak");
        File temp = new File(parent, file.getName() + ".schema.tmp");

        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            yaml.save(temp);
            YamlConfiguration verified = loadStrict(temp);
            if (detectSchema(verified) != CURRENT_SCHEMA || verified.getConfigurationSection("shops") == null) {
                throw new IOException("Hasil migration shops.yml gagal verifikasi.");
            }
            moveReplace(temp, file);
        } catch (IOException exception) {
            Files.deleteIfExists(temp.toPath());
            throw exception;
        }

        if (logger != null) {
            logger.warning("shops.yml schema migrated v" + LEGACY_SCHEMA + " -> v" + CURRENT_SCHEMA
                    + ". Backup: " + backup.getName());
        }
        return new SchemaStatus(CURRENT_SCHEMA, true, true,
                "migrated v" + LEGACY_SCHEMA + " -> v" + CURRENT_SCHEMA);
    }

    public static void stampMutation(YamlConfiguration yaml) {
        yaml.set("meta.schema", CURRENT_SCHEMA);
        yaml.set("meta.updated-at", Instant.now().toString());
    }

    public static int detectSchema(YamlConfiguration yaml) throws IOException {
        if (!yaml.contains("meta.schema")) {
            return LEGACY_SCHEMA;
        }
        Object raw = yaml.get("meta.schema");
        if (!(raw instanceof Number number)) {
            throw new IOException("meta.schema harus berupa integer.");
        }
        double asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)) {
            throw new IOException("meta.schema harus berupa integer.");
        }
        return number.intValue();
    }

    public static void validateSupported(int schema) throws IOException {
        if (schema < LEGACY_SCHEMA) {
            throw new IOException("shops.yml schema tidak valid: " + schema);
        }
        if (schema > CURRENT_SCHEMA) {
            throw new IOException("shops.yml schema " + schema + " lebih baru dari yang didukung plugin ("
                    + CURRENT_SCHEMA + "). Upgrade plugin sebelum memuat file ini.");
        }
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

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record SchemaStatus(int schema, boolean current, boolean migrated, String detail) {
    }
}
