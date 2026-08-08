package com.aprism.prismate.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Prismate configuration, read from {@code <gameDir>/prismate/prismate.json}.
 * A missing or unreadable file yields the defaults; a syntactically broken file
 * logs a visible warning and falls back to defaults (docs 01 DECISION-2).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code enabled} (default {@code true}): master switch; when false,
 *       Prismate performs no work at all.</li>
 *   <li>{@code extraAjeDirs} (default empty): additional directories scanned
 *       for {@code *.aje} packs besides {@code <gameDir>/mods/}.</li>
 * </ul>
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateConfig {

    private static final Logger LOG = Logger.getLogger("prismate");

    /** The config file location relative to the game directory. */
    public static final String CONFIG_RELATIVE_PATH = "prismate/prismate.json";

    private boolean enabled = true;
    private List<String> extraAjeDirs = new ArrayList<>();

    /**
     * Loads the configuration for the given game directory. Never throws:
     * any problem falls back to defaults with a visible log message.
     *
     * @param gameDir the game instance root
     * @return the loaded (or default) configuration
     */
    public static PrismateConfig load(Path gameDir) {
        Path file = gameDir.resolve(CONFIG_RELATIVE_PATH);
        if (!Files.isRegularFile(file)) {
            return new PrismateConfig();
        }
        try {
            String json = Files.readString(file);
            PrismateConfig parsed = new Gson().fromJson(json, PrismateConfig.class);
            if (parsed == null) {
                LOG.warning("Prismate config " + file + " is empty; using defaults");
                return new PrismateConfig();
            }
            if (parsed.extraAjeDirs == null) {
                parsed.extraAjeDirs = new ArrayList<>();
            }
            return parsed;
        } catch (IOException e) {
            LOG.warning("Prismate config " + file + " could not be read ("
                    + e.getMessage() + "); using defaults");
            return new PrismateConfig();
        } catch (JsonSyntaxException e) {
            LOG.warning("Prismate config " + file + " is not valid JSON ("
                    + e.getMessage() + "); using defaults");
            return new PrismateConfig();
        }
    }

    /**
     * @return whether Prismate is enabled at all
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return extra directories (beyond {@code mods/}) to scan for {@code .aje}
     *         packs, relative or absolute paths as configured
     */
    public List<String> getExtraAjeDirs() {
        return List.copyOf(extraAjeDirs);
    }
}
