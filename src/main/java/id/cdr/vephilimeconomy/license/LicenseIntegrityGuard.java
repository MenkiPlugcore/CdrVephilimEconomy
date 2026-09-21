package id.cdr.vephilimeconomy.license;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * Lightweight anti-tamper guard for the embedded MENKIESTES license notice.
 *
 * <p>This is a deterrent, not DRM. A determined party with full control of the
 * source or bytecode can patch software. The legal LICENSE remains the primary
 * protection. This guard makes casual removal/modification of the embedded
 * license fail closed at plugin startup.</p>
 */
public final class LicenseIntegrityGuard {
    private static final String RESOURCE = "META-INF/MENKIESTES-LICENSE.txt";
    private static final String EXPECTED_SHA256 =
            "404e9fde89c98ade3cbce360729500ee7801914fd18be47e28727bafad3989c3";

    private LicenseIntegrityGuard() {
    }

    public static boolean verify(Logger logger) {
        try (InputStream input = LicenseIntegrityGuard.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                logger.severe("MENKIESTES license integrity check FAILED: embedded license resource is missing.");
                logger.severe("Plugin will not start because the protected license notice was removed or the JAR is incomplete.");
                return false;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }

            String actual = HexFormat.of().formatHex(digest.digest());
            if (!EXPECTED_SHA256.equalsIgnoreCase(actual)) {
                logger.severe("MENKIESTES license integrity check FAILED: embedded license fingerprint mismatch.");
                logger.severe("Expected=" + EXPECTED_SHA256 + ", actual=" + actual + ".");
                logger.severe("Plugin will not start because the embedded license notice was modified.");
                return false;
            }

            logger.info("MENKIESTES SOFTWARE LICENSE v1.0 integrity verified. Copyright (c) 2026 CADERA.");
            return true;
        } catch (IOException | NoSuchAlgorithmException exception) {
            logger.severe("MENKIESTES license integrity check FAILED: " + exception.getMessage());
            return false;
        }
    }
}
