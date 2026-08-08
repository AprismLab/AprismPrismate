package com.aprism.prismate.discovery;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.aprism.api.Environment;
import com.aprism.manifest.AprismManifest;
import com.aprism.manifest.ManifestParseException;
import com.aprism.manifest.ManifestParser;
import com.aprism.manifest.ManifestValidator;
import com.aprism.prismate.host.EnvSide;

/**
 * Discovers Aprism-native {@code .aje} packs for Prismate (docs 01 Section 6,
 * step 1-2). Scans the given directories recursively for {@code *.aje},
 * parses each pack's {@code aprism.manifest.json} with Aprism's own
 * {@link ManifestParser} (reused, so manifest semantics cannot drift from
 * Aprism core), validates it with {@link ManifestValidator}, and filters by
 * side environment.
 *
 * <p>Every malformed pack produces a named {@link LoadFailure}; discovery
 * never throws and never silently skips a broken pack. Packs whose declared
 * environment does not match the running side (e.g. a client-only pack on a
 * dedicated server) are skipped as normal, mirroring host-loader convention,
 * and are logged for visibility.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class AjeDiscovery {

    /** The required manifest entry inside every {@code .aje}. */
    public static final String MANIFEST_ENTRY = "aprism.manifest.json";

    /** A discovered, parsed, validated {@code .aje} pack. */
    public record DiscoveredAje(Path path, AprismManifest manifest) {
    }

    private final ManifestParser parser = new ManifestParser();
    private final ManifestValidator validator = new ManifestValidator();

    /**
     * Scans the given directories for {@code .aje} packs.
     *
     * @param scanDirs the directories to scan recursively (missing dirs skipped)
     * @param side     the distribution side of this boot
     * @param failures collects per-pack load failures (never throws)
     * @return the discovered packs in deterministic order (path, then file name)
     */
    public List<DiscoveredAje> discover(List<Path> scanDirs, EnvSide side, List<LoadFailure> failures) {
        List<Path> ajeFiles = new ArrayList<>();
        for (Path dir : scanDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".aje"))
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .forEachOrdered(ajeFiles::add);
            } catch (IOException e) {
                failures.add(new LoadFailure(LoadFailure.DISCOVERY, null, dir.getFileName().toString(),
                        "scan of " + dir + " failed: " + e.getMessage()));
            }
        }

        List<DiscoveredAje> discovered = new ArrayList<>();
        List<String> seenIds = new ArrayList<>();
        for (Path aje : ajeFiles) {
            String fileName = aje.getFileName().toString();
            AprismManifest manifest = parseManifest(aje, fileName, failures);
            if (manifest == null) {
                continue;
            }
            ManifestValidator.ValidationResult validation = validator.validate(manifest);
            if (!validation.valid()) {
                failures.add(new LoadFailure(LoadFailure.DISCOVERY, manifest.id(), fileName,
                        "invalid manifest: " + String.join("; ", validation.errors())));
                continue;
            }
            if (seenIds.contains(manifest.id())) {
                failures.add(new LoadFailure(LoadFailure.DISCOVERY, manifest.id(), fileName,
                        "duplicate mod id '" + manifest.id() + "'; an earlier pack with this id "
                                + "was already discovered"));
                continue;
            }
            Environment env = Environment.parse(manifest.environment());
            if (!side.matches(env)) {
                java.util.logging.Logger.getLogger("prismate").info("Skipping " + fileName
                        + ": environment '" + manifest.environment() + "' does not match this "
                        + side + " instance");
                continue;
            }
            seenIds.add(manifest.id());
            discovered.add(new DiscoveredAje(aje, manifest));
        }
        return discovered;
    }

    /**
     * Opens the pack and parses its manifest entry with Aprism's parser.
     *
     * @param aje      the pack path
     * @param fileName the pack file name (for failure reporting)
     * @param failures collects failures
     * @return the parsed manifest, or {@code null} if the pack is unreadable
     */
    private AprismManifest parseManifest(Path aje, String fileName, List<LoadFailure> failures) {
        try (FileSystem fs = FileSystems.newFileSystem(aje, (ClassLoader) null)) {
            Path manifestPath = fs.getPath(MANIFEST_ENTRY);
            if (!Files.exists(manifestPath)) {
                failures.add(new LoadFailure(LoadFailure.DISCOVERY, null, fileName,
                        "missing required entry " + MANIFEST_ENTRY));
                return null;
            }
            try {
                return parser.parse(manifestPath);
            } catch (ManifestParseException e) {
                failures.add(new LoadFailure(LoadFailure.DISCOVERY, null, fileName,
                        "unparseable manifest: " + e.getMessage()));
                return null;
            }
        } catch (IOException e) {
            failures.add(new LoadFailure(LoadFailure.DISCOVERY, null, fileName,
                    "cannot open pack: " + e.getMessage()));
            return null;
        } catch (RuntimeException e) {
            // The JDK zip provider rejects some malformed or hostile archives
            // with runtime exceptions (e.g. ProviderNotFoundException); treat
            // every such case as a named, readable failure.
            failures.add(new LoadFailure(LoadFailure.DISCOVERY, null, fileName,
                    "cannot open pack: " + e));
            return null;
        }
    }
}
