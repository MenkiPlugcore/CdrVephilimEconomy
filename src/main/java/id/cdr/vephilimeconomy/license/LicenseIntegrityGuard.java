package id.cdr.vephilimeconomy.license;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Mandatory MENKIESTES license integrity guard.
 *
 * <p>The legal notice is embedded in the JAR and copied to LICENSE.txt on the
 * first legitimate server start. After initialization, deleting or modifying
 * LICENSE.txt causes startup to fail. A periodic runtime monitor also disables
 * the plugin if the file is removed or changed while the server is online.</p>
 *
 * <p>This is a local integrity/tamper deterrent, not unbreakable DRM. A party
 * with full bytecode/source control can patch local checks. The repository
 * LICENSE remains the legal source of rights and restrictions.</p>
 */
public final class LicenseIntegrityGuard {
    private static final String EMBEDDED_RESOURCE = "META-INF/MENKIESTES-LICENSE.txt";
    private static final String LICENSE_FILE_NAME = "LICENSE.txt";
    private static final String INSTALL_MARKER_NAME = ".cdrvephilimeconomy-license-state.yml";
    private static final String PRODUCT = "CdrVephilimEconomy";
    private static final String LICENSE_NAME = "MENKIESTES SOFTWARE LICENSE v1.0";
    private static final String EXPECTED_SHA256 =
            "404e9fde89c98ade3cbce360729500ee7801914fd18be47e28727bafad3989c3";
    private static final String SIGNATURE_SALT =
            "CADERA-MENKIESTES-CDRVEPHILIMECONOMY-LICENSE-GUARD-v1";

    private final CdrVephilimEconomy plugin;
    private File licenseFile;
    private File markerFile;
    private byte[] templateBytes;
    private String templateHash;
    private BukkitTask monitorTask;
    private boolean violationTriggered;

    public LicenseIntegrityGuard(CdrVephilimEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Generates the runtime license on first start and verifies all integrity
     * evidence on subsequent starts.
     */
    public void initializeOrThrow() {
        try {
            templateBytes = readEmbeddedLicense();
            templateHash = sha256(templateBytes);
            if (!EXPECTED_SHA256.equalsIgnoreCase(templateHash)) {
                throw new LicenseIntegrityException(
                        "Embedded MENKIESTES license fingerprint mismatch. Expected "
                                + EXPECTED_SHA256 + " but found " + templateHash + "."
                );
            }

            File dataFolder = plugin.getDataFolder();
            File pluginsFolder = dataFolder.getParentFile();
            if (pluginsFolder == null) {
                throw new LicenseIntegrityException("Unable to resolve the server plugins directory.");
            }

            licenseFile = new File(dataFolder, LICENSE_FILE_NAME);
            markerFile = new File(pluginsFolder, INSTALL_MARKER_NAME);

            boolean licenseExists = licenseFile.isFile();
            boolean markerExists = markerFile.isFile();

            if (!licenseExists && !markerExists) {
                bootstrapNewInstallation();
            } else if (licenseExists && !markerExists) {
                // Migration/copy case: only accept an untouched legal notice.
                verifyLicenseFile();
                writeInstallMarker(UUID.randomUUID().toString(), Instant.now().toString());
                plugin.getLogger().info("License state marker created for existing valid LICENSE.txt.");
            }

            verifyExistingInstallation();
            plugin.getLogger().info("MENKIESTES SOFTWARE LICENSE v1.0 integrity: VALID.");
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    PRODUCT + " license initialization failed: " + exception.getMessage(), exception);
        } catch (LicenseIntegrityException exception) {
            throw new IllegalStateException(
                    PRODUCT + " LICENSE INTEGRITY FAILURE: " + exception.getMessage()
                            + " Restore the original " + LICENSE_FILE_NAME + " before starting the plugin.",
                    exception
            );
        }
    }

    /**
     * Checks the runtime license continuously. Default 100 ticks = 5 seconds.
     */
    public void startMonitoring() {
        stopMonitoring();
        violationTriggered = false;
        long ticks = Math.max(20L,
                plugin.getConfig().getLong("production.license-integrity-check-ticks", 100L));
        monitorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (violationTriggered) {
                return;
            }
            try {
                verifyExistingInstallation();
            } catch (Exception exception) {
                violationTriggered = true;
                plugin.getLogger().severe("============================================================");
                plugin.getLogger().severe("CDRVEPHILIMECONOMY LICENSE INTEGRITY FAILURE");
                plugin.getLogger().severe(exception.getMessage());
                plugin.getLogger().severe("The plugin will now be disabled.");
                plugin.getLogger().severe("============================================================");
                plugin.getServer().getPluginManager().disablePlugin(plugin);
            }
        }, ticks, ticks);
    }

    public void stopMonitoring() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }

    public String statusName() {
        try {
            verifyExistingInstallation();
            return "VALID";
        } catch (Exception ignored) {
            return "INVALID";
        }
    }

    public File licenseFile() {
        return licenseFile;
    }

    private void bootstrapNewInstallation() throws IOException, NoSuchAlgorithmException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Unable to create plugin data directory: " + dataFolder.getAbsolutePath());
        }

        atomicWrite(licenseFile.toPath(), templateBytes);
        String installationId = UUID.randomUUID().toString();
        writeInstallMarker(installationId, Instant.now().toString());
        plugin.getLogger().info("Generated required runtime license file: " + licenseFile.getPath());
    }

    private void verifyExistingInstallation()
            throws IOException, NoSuchAlgorithmException, LicenseIntegrityException {
        if (licenseFile == null || markerFile == null || templateHash == null) {
            throw new LicenseIntegrityException("License guard is not initialized.");
        }
        if (!markerFile.isFile()) {
            throw new LicenseIntegrityException(
                    "Installation license marker is missing: " + markerFile.getName());
        }
        verifyLicenseFile();
        verifyInstallMarker();
    }

    private void verifyLicenseFile()
            throws IOException, NoSuchAlgorithmException, LicenseIntegrityException {
        if (!licenseFile.isFile()) {
            throw new LicenseIntegrityException(
                    "Required " + LICENSE_FILE_NAME + " is missing from plugins/"
                            + PRODUCT + "/."
            );
        }
        String currentHash = sha256(Files.readAllBytes(licenseFile.toPath()));
        if (!templateHash.equalsIgnoreCase(currentHash)) {
            throw new LicenseIntegrityException(
                    LICENSE_FILE_NAME + " was modified. Expected SHA-256 " + templateHash
                            + " but found " + currentHash + "."
            );
        }
    }

    private void verifyInstallMarker()
            throws IOException, LicenseIntegrityException, NoSuchAlgorithmException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(markerFile);
        String product = yaml.getString("product", "");
        String license = yaml.getString("license", "");
        String installationId = yaml.getString("installation-id", "");
        String storedHash = yaml.getString("template-sha256", "");
        String signature = yaml.getString("signature", "");

        if (!PRODUCT.equals(product)) {
            throw new LicenseIntegrityException("Installation marker has an invalid product identifier.");
        }
        if (!LICENSE_NAME.equals(license)) {
            throw new LicenseIntegrityException("Installation marker has an invalid license identifier.");
        }
        if (installationId.isBlank()) {
            throw new LicenseIntegrityException("Installation marker has no installation ID.");
        }
        if (!templateHash.equalsIgnoreCase(storedHash)) {
            throw new LicenseIntegrityException("Installation marker license hash does not match this build.");
        }

        String expectedSignature = stateSignature(installationId, storedHash);
        if (!expectedSignature.equalsIgnoreCase(signature)) {
            throw new LicenseIntegrityException("Installation marker signature is invalid.");
        }
    }

    private void writeInstallMarker(String installationId, String createdAt)
            throws IOException, NoSuchAlgorithmException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("product", PRODUCT);
        yaml.set("license", LICENSE_NAME);
        yaml.set("installation-id", installationId);
        yaml.set("created-at", createdAt);
        yaml.set("template-sha256", templateHash);
        yaml.set("signature", stateSignature(installationId, templateHash));
        yaml.set("notice", "Do not remove or alter plugins/" + PRODUCT + "/" + LICENSE_FILE_NAME + ".");
        atomicWrite(markerFile.toPath(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readEmbeddedLicense() throws IOException {
        try (InputStream stream = LicenseIntegrityGuard.class.getClassLoader()
                .getResourceAsStream(EMBEDDED_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Bundled MENKIESTES license is missing from the plugin JAR.");
            }
            return stream.readAllBytes();
        }
    }

    private String stateSignature(String installationId, String hash)
            throws NoSuchAlgorithmException {
        return sha256((PRODUCT + "|" + LICENSE_NAME + "|" + installationId + "|"
                + hash + "|" + SIGNATURE_SALT).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static final class LicenseIntegrityException extends Exception {
        private LicenseIntegrityException(String message) {
            super(message);
        }
    }
}
