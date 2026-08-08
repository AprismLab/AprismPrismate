package com.aprism.prismate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Build-time identity of this Prismate build, loaded from the generated
 * {@code prismate-build-info.properties} resource. Carries the Prismate
 * version, the embedded Aprism core version, the target Minecraft version,
 * and the host loader version pins.
 *
 * <p>The embedded Aprism version is injected into the dependency environment
 * as the {@code aprism} environment id (docs 01 Section 7, OPEN-1 interim).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateVersion {

    private static final Logger LOG = Logger.getLogger("prismate");

    private static final String RESOURCE = "prismate-build-info.properties";

    private static volatile String prismateVersion = "unknown";
    private static volatile String embeddedAprism = "unknown";
    private static volatile String minecraftEdition = "JE";
    private static volatile String minecraftVersion = "unknown";
    private static volatile String pinFabricLoader = "unknown";
    private static volatile String pinFml = "unknown";
    private static volatile boolean loaded = false;

    private PrismateVersion() {
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream in = PrismateVersion.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warning(RESOURCE + " not found on classpath; version info unavailable");
                return;
            }
            Properties props = new Properties();
            props.load(in);
            prismateVersion = props.getProperty("prismate.version", prismateVersion);
            embeddedAprism = props.getProperty("prismate.embedded.aprism", embeddedAprism);
            minecraftEdition = props.getProperty("prismate.minecraft.edition", minecraftEdition);
            minecraftVersion = props.getProperty("prismate.minecraft.version", minecraftVersion);
            pinFabricLoader = props.getProperty("prismate.pin.fabricloader", pinFabricLoader);
            pinFml = props.getProperty("prismate.pin.fml", pinFml);
        } catch (IOException e) {
            LOG.warning("Failed to read " + RESOURCE + ": " + e.getMessage());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /**
     * @return this Prismate build's public version (e.g. {@code v26.0-Alpha.1})
     */
    public static String prismateVersion() {
        ensureLoaded();
        return prismateVersion;
    }

    /**
     * @return the embedded Aprism core version (e.g. {@code v26.0-Alpha.8})
     */
    public static String embeddedAprismVersion() {
        ensureLoaded();
        return embeddedAprism;
    }

    /**
     * Returns the embedded Aprism version normalized for dependency matching:
     * the leading {@code v} and any pre-release suffix are stripped, and the
     * result is padded to three segments (mirrors the normalization Aprism's
     * ExtensionLoader applies to the running Aprism version).
     *
     * @return e.g. {@code 26.0.0} for {@code v26.0-Alpha.8}
     */
    public static String embeddedAprismVersionNormalized() {
        return normalizeAprismVersion(embeddedAprismVersion());
    }

    /**
     * Normalizes an Aprism version string for SemVer range matching.
     *
     * @param raw the raw version (e.g. {@code v26.0-Alpha.8})
     * @return the normalized three-segment version (e.g. {@code 26.0.0})
     */
    public static String normalizeAprismVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "0.0.0";
        }
        String v = raw.trim();
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        int dash = v.indexOf('-');
        if (dash >= 0) {
            v = v.substring(0, dash);
        }
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        String[] parts = v.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(i < parts.length && !parts[i].isEmpty() ? parts[i] : "0");
        }
        return sb.toString();
    }

    /**
     * @return the target Minecraft edition ({@code JE})
     */
    public static String minecraftEdition() {
        ensureLoaded();
        return minecraftEdition;
    }

    /**
     * @return the target Minecraft version (e.g. {@code 26.2})
     */
    public static String minecraftVersion() {
        ensureLoaded();
        return minecraftVersion;
    }

    /**
     * @return the Fabric Loader version this build was compiled against
     */
    public static String pinFabricLoader() {
        ensureLoaded();
        return pinFabricLoader;
    }

    /**
     * @return the FML (NeoForge) version this build was compiled against
     */
    public static String pinFml() {
        ensureLoaded();
        return pinFml;
    }
}
