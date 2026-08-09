package com.aprism.prismate.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.aprism.manifest.AprismManifest;
import com.aprism.prismate.discovery.AjeDiscovery;
import com.aprism.prismate.discovery.LoadFailure;

/**
 * Unpacks a discovered {@code .aje} into its per-mod working directory
 * (docs 01 Section 6, step 4): {@code <workRoot>/<modid>/} containing the
 * main {@code <modid>.jar}, {@code resources/}, {@code mixins/}, and
 * {@code lib/} (JiJ-style bundled dependency jars, DECISION-3).
 *
 * <p>Structural purity (Aprism doc 07 Sections 3 and 9) is enforced during
 * extraction: exactly one root-level jar named {@code <modid>.jar} after the
 * manifest id; no jars under {@code resources/} or {@code mixins/}; no
 * per-loader subdirectories. Violations produce a named {@link LoadFailure}.
 *
 * <p>Security: every extracted entry is resolved and normalized against the
 * target directory before writing (zip-slip defense), and total extracted
 * bytes and entry counts are capped (zip-bomb defense).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class AjeExtractor {

    /** Directory names that must never appear at the root of a {@code .aje}. */
    private static final List<String> FORBIDDEN_ROOT_DIRS =
            List.of("fabric", "forge", "neoforge", "quilt", "liteloader");

    /** Maximum total extracted bytes per pack (zip-bomb guard). */
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /** Maximum number of entries per pack (zip-bomb guard). */
    private static final int MAX_ENTRIES = 10_000;

    /** A fully extracted pack ready for classpath injection. */
    public record ExtractedPack(
            AprismManifest manifest,
            Path sourceAje,
            Path workDir,
            List<Path> jars,
            Path resourcesDir,
            Path mixinsDir,
            List<String> mixinConfigs,
            Path iconPath) {
    }

    /**
     * Extracts one pack. Never throws; returns {@code null} after recording a
     * named failure when the pack is malformed.
     *
     * @param aje       the discovered pack
     * @param workRoot  the per-mod working root ({@code <gameDir>/prismate/work})
     * @param failures  collects load failures
     * @return the extracted pack, or {@code null} on failure
     */
    public ExtractedPack extract(AjeDiscovery.DiscoveredAje aje, Path workRoot, List<LoadFailure> failures) {
        AprismManifest manifest = aje.manifest();
        String modId = manifest.id();
        String fileName = aje.path().getFileName().toString();
        long t0 = System.nanoTime();
        try (FileSystem fs = FileSystems.newFileSystem(aje.path(), (ClassLoader) null)) {
            // --- Structural validation pass (before writing anything) ---
            List<Path> rootEntries;
            try (Stream<Path> stream = Files.list(fs.getPath("/"))) {
                rootEntries = stream.toList();
            }
            List<Path> rootJars = rootEntries.stream()
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList();
            if (rootJars.isEmpty()) {
                fail(failures, modId, fileName, "no main mod jar at the pack root", t0);
                return null;
            }
            if (rootJars.size() > 1) {
                fail(failures, modId, fileName, "more than one jar at the pack root ("
                        + rootJars.size() + "); exactly one <modid>.jar is allowed", t0);
                return null;
            }
            String expectedJar = modId + ".jar";
            if (!rootJars.get(0).getFileName().toString().equals(expectedJar)) {
                fail(failures, modId, fileName, "main jar is named '"
                        + rootJars.get(0).getFileName() + "' but must be '" + expectedJar
                        + "' (named after the manifest id)", t0);
                return null;
            }
            for (Path root : rootEntries) {
                String name = root.getFileName().toString().toLowerCase();
                if (Files.isDirectory(root) && FORBIDDEN_ROOT_DIRS.contains(name)) {
                    fail(failures, modId, fileName, "per-loader subdirectory '"
                            + root.getFileName() + "' is forbidden; .aje is Aprism-native only "
                            + "(no loader subdirectories)", t0);
                    return null;
                }
            }

            // --- Extraction pass ---
            Path workDir = workRoot.resolve(modId);
            cleanDirectory(workDir);
            Files.createDirectories(workDir);

            long[] totalBytes = {0};
            int[] totalEntries = {0};
            Path resourcesTarget = workDir.resolve("resources");
            Path mixinsTarget = workDir.resolve("mixins");
            Path libTarget = workDir.resolve("lib");
            List<Path> jars = new ArrayList<>();

            try (Stream<Path> walk = Files.walk(fs.getPath("/"), FileVisitOption.FOLLOW_LINKS)) {
                List<Path> entries = walk
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path entry : entries) {
                    totalEntries[0]++;
                    if (totalEntries[0] > MAX_ENTRIES) {
                        fail(failures, modId, fileName, "pack has more than " + MAX_ENTRIES
                                + " entries; refusing to extract", t0);
                        return null;
                    }
                    String rel = fs.getPath("/").relativize(entry).toString()
                            .replace('\\', '/');
                    // Zip-slip defense: the normalized target must stay inside workDir
                    Path target = workDir.resolve(rel).normalize();
                    if (!target.startsWith(workDir)) {
                        fail(failures, modId, fileName, "entry '" + rel
                                + "' escapes the working directory; refusing to extract", t0);
                        return null;
                    }
                    // Jars are forbidden below resources/ and mixins/
                    if (rel.endsWith(".jar")
                            && (rel.startsWith("resources/") || rel.startsWith("mixins/"))) {
                        fail(failures, modId, fileName, "jar '" + rel
                                + "' inside resources/ or mixins/ violates the .aje contract", t0);
                        return null;
                    }
                    long size = Files.size(entry);
                    totalBytes[0] += size;
                    if (totalBytes[0] > MAX_TOTAL_BYTES) {
                        fail(failures, modId, fileName, "pack exceeds the "
                                + (MAX_TOTAL_BYTES / 1024 / 1024) + " MiB extraction budget; "
                                + "refusing to extract", t0);
                        return null;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = Files.newInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (rel.endsWith(".jar") && !rel.contains("/")) {
                        jars.add(0, target); // main jar first
                    } else if (rel.startsWith("lib/") && rel.endsWith(".jar")) {
                        jars.add(target);
                    }
                }
            }

            Path resourcesDir = Files.isDirectory(resourcesTarget) ? resourcesTarget : null;
            Path mixinsDir = Files.isDirectory(mixinsTarget) ? mixinsTarget : null;
            List<String> mixinConfigs = listMixinConfigs(mixinsDir);

            // Warn when manifest-declared mixin configs are absent on disk
            if (manifest.mixins() != null) {
                for (String declared : manifest.mixins()) {
                    if (!mixinConfigs.contains(declared)) {
                        java.util.logging.Logger.getLogger("prismate").warning("Pack " + fileName
                                + " declares mixin config '" + declared
                                + "' but it was not found under mixins/");
                    }
                }
            }

            // The optional icon.png at the pack root was already copied into
            // workDir by the generic extraction loop; surface its path so host
            // bridges can pass display metadata (icon + displayName) through to
            // the host loader's mod list where supported (v26.0-Alpha.7).
            Path iconCandidate = workDir.resolve("icon.png");
            Path iconPath = Files.isRegularFile(iconCandidate) ? iconCandidate : null;

            return new ExtractedPack(manifest, aje.path(), workDir, List.copyOf(jars),
                    resourcesDir, mixinsDir, mixinConfigs, iconPath);
        } catch (IOException e) {
            fail(failures, modId, fileName, "extraction failed: " + e.getMessage(), t0);
            return null;
        } catch (RuntimeException e) {
            fail(failures, modId, fileName, "extraction failed: " + e, t0);
            return null;
        }
    }

    /**
     * Deletes the contents of a directory if it exists (stale extraction guard).
     */
    private void cleanDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException("cannot clean " + p, e);
                }
            });
        }
    }

    /**
     * Lists the {@code *.json} mixin config names under the mixins directory.
     */
    private List<String> listMixinConfigs(Path mixinsDir) throws IOException {
        if (mixinsDir == null) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(mixinsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private void fail(List<LoadFailure> failures, String modId, String fileName,
            String reason, long t0) {
        java.util.logging.Logger.getLogger("prismate").warning("Extraction failed for "
                + fileName + ": " + reason);
        failures.add(new LoadFailure(LoadFailure.EXTRACTION, modId, fileName, reason));
    }
}
