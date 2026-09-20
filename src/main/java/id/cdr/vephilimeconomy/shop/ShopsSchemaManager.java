package id.cdr.vephilimeconomy.shop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.logging.Logger;

public final class ShopsSchemaManager {
    public static final int LEGACY_SCHEMA = 1;
    public static final int CURRENT_SCHEMA = 2;
    private static final int MIGRATION_MARKER_SCHEMA = 1;

    private ShopsSchemaManager() {
    }

    public static SchemaStatus inspect(File file) throws IOException {
        YamlConfiguration yaml = loadStrict(file);
        int schema = detectSchema(yaml);
        validateSupported(schema);
        return new SchemaStatus(schema, schema == CURRENT_SCHEMA, false,
                schema == CURRENT_SCHEMA ? "schema current" : "legacy schema requires migration");
    }

    public static boolean hasPendingRecovery(File file) {
        File parent = file.getParentFile();
        return new File(parent, file.getName() + ".schema.pending").isFile();
    }

    public static SchemaStatus ensureCurrent(File file, Logger logger) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Tidak dapat membuat data directory untuk schema migration.");
        }

        File backup = new File(parent, file.getName() + ".schema-v" + LEGACY_SCHEMA + ".bak");
        File temp = new File(parent, file.getName() + ".schema.tmp");
        File pending = new File(parent, file.getName() + ".schema.pending");
        File last = new File(parent, file.getName() + ".schema.last");

        if (pending.isFile()) {
            recoverInterruptedMigration(file, backup, temp, pending, last, logger);
        }

        YamlConfiguration yaml = loadStrict(file);
        int schema = detectSchema(yaml);
        validateSupported(schema);

        if (schema == CURRENT_SCHEMA) {
            Files.deleteIfExists(temp.toPath());
            return new SchemaStatus(schema, true, false, "schema current");
        }

        if (schema != LEGACY_SCHEMA) {
            throw new IOException("shops.yml schema " + schema + " tidak memiliki migration path ke " + CURRENT_SCHEMA + ".");
        }

        ConfigurationSection shops = yaml.getConfigurationSection("shops");
        if (shops == null) {
            throw new IOException("shops.yml legacy tidak memiliki section 'shops'.");
        }

        byte[] original = Files.readAllBytes(file.toPath());
        String sourceHash = sha256(original);

        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        byte[] backupBytes = Files.readAllBytes(backup.toPath());
        if (!MessageDigest.isEqual(original, backupBytes)) {
            throw new IOException("Backup schema migration tidak identik dengan shops.yml sumber. Migration dibatalkan.");
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

        Instant now = Instant.now();
        yaml.set("meta.schema", CURRENT_SCHEMA);
        yaml.set("meta.migrated-from", LEGACY_SCHEMA);
        yaml.set("meta.migrated-at", now.toString());
        yaml.set("meta.updated-at", now.toString());

        try {
            yaml.save(temp);
            YamlConfiguration verified = loadStrict(temp);
            if (detectSchema(verified) != CURRENT_SCHEMA || verified.getConfigurationSection("shops") == null) {
                throw new IOException("Hasil migration shops.yml gagal verifikasi.");
            }

            String candidateHash = sha256(Files.readAllBytes(temp.toPath()));
            writePendingMarker(pending, sourceHash, candidateHash, now);
            moveReplace(temp, file);

            YamlConfiguration finalYaml = loadStrict(file);
            if (detectSchema(finalYaml) != CURRENT_SCHEMA || finalYaml.getConfigurationSection("shops") == null) {
                throw new IOException("shops.yml hasil migration gagal verifikasi setelah replace.");
            }
            String liveHash = sha256(Files.readAllBytes(file.toPath()));
            if (!liveHash.equals(candidateHash)) {
                throw new IOException("Hash shops.yml hasil migration berbeda dari candidate yang sudah diverifikasi.");
            }
        } catch (IOException exception) {
            Files.deleteIfExists(temp.toPath());
            throw exception;
        }

        archivePendingMarker(pending, last, logger);

        if (logger != null) {
            logger.warning("shops.yml schema migrated v" + LEGACY_SCHEMA + " -> v" + CURRENT_SCHEMA
                    + ". Backup verified: " + backup.getName());
        }
        return new SchemaStatus(CURRENT_SCHEMA, true, true,
                "migrated v" + LEGACY_SCHEMA + " -> v" + CURRENT_SCHEMA + " with verified backup");
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

    private static void recoverInterruptedMigration(File file, File backup, File temp, File pending,
                                                    File last, Logger logger) throws IOException {
        try {
            YamlConfiguration live = loadStrict(file);
            int liveSchema = detectSchema(live);
            validateSupported(liveSchema);
            if (live.getConfigurationSection("shops") == null) {
                throw new IOException("shops.yml tidak memiliki section 'shops'.");
            }

            Files.deleteIfExists(temp.toPath());
            if (liveSchema == CURRENT_SCHEMA) {
                if (logger != null) {
                    logger.warning("Recovery schema migration: shops.yml sudah v" + CURRENT_SCHEMA
                            + "; pending marker lama diarsipkan sebagai committed recovery.");
                }
                archivePendingMarker(pending, last, logger);
                return;
            }

            if (logger != null) {
                logger.warning("Recovery schema migration: shops.yml masih legacy v" + liveSchema
                        + "; migration akan diulang dari file live yang valid.");
            }
            archivePendingMarker(pending, last, logger);
            return;
        } catch (IOException liveFailure) {
            if (!backup.isFile()) {
                throw new IOException("Interrupted schema migration terdeteksi, shops.yml tidak sehat, dan backup migration tidak tersedia: "
                        + liveFailure.getMessage(), liveFailure);
            }

            YamlConfiguration backupYaml = loadStrict(backup);
            int backupSchema = detectSchema(backupYaml);
            validateSupported(backupSchema);
            if (backupYaml.getConfigurationSection("shops") == null) {
                throw new IOException("Backup migration tidak memiliki section 'shops'.");
            }

            File recoveryTemp = new File(file.getParentFile(), file.getName() + ".schema.recover.tmp");
            try {
                Files.copy(backup.toPath(), recoveryTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                loadStrict(recoveryTemp);
                moveReplace(recoveryTemp, file);
            } finally {
                Files.deleteIfExists(recoveryTemp.toPath());
                Files.deleteIfExists(temp.toPath());
            }

            if (logger != null) {
                logger.severe("Interrupted schema migration dipulihkan dari backup " + backup.getName()
                        + ". Migration akan diverifikasi dan dijalankan ulang sebelum shop management aktif.");
            }
            archivePendingMarker(pending, last, logger);
        }
    }

    private static void writePendingMarker(File pending, String sourceHash, String candidateHash, Instant startedAt)
            throws IOException {
        YamlConfiguration marker = new YamlConfiguration();
        marker.set("meta.schema", MIGRATION_MARKER_SCHEMA);
        marker.set("state", "PREPARED");
        marker.set("from-schema", LEGACY_SCHEMA);
        marker.set("to-schema", CURRENT_SCHEMA);
        marker.set("started-at", startedAt.toString());
        marker.set("source-sha256", sourceHash);
        marker.set("candidate-sha256", candidateHash);
        marker.save(pending);
        loadStrict(pending);
    }

    private static void archivePendingMarker(File pending, File last, Logger logger) {
        if (!pending.isFile()) {
            return;
        }
        try {
            Files.move(pending.toPath(), last.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            if (logger != null) {
                logger.warning("Gagal mengarsipkan schema pending marker; marker dipertahankan untuk recovery berikutnya: "
                        + exception.getMessage());
            }
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 tidak tersedia di runtime Java.", exception);
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
