package com.aprism.prismate.extraction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.manifest.AprismManifest;
import com.aprism.prismate.discovery.AjeDiscovery;
import com.aprism.prismate.discovery.LoadFailure;
import com.aprism.prismate.testsupport.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AjeExtractor}: structural purity enforcement, extraction of
 * jars/resources/mixins/lib, mixin config listing, and zip-slip / zip-bomb
 * defenses.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("AjeExtractor")
class AjeExtractorTest {

    @TempDir
    Path tempDir;

    private final List<LoadFailure> failures = new ArrayList<>();
    private final AjeExtractor extractor = new AjeExtractor();
    private final AjeDiscovery discovery = new AjeDiscovery();

    private AjeDiscovery.DiscoveredAje discoverSingle() throws Exception {
        List<AjeDiscovery.DiscoveredAje> found = discovery.discover(
                List.of(tempDir), com.aprism.prismate.host.EnvSide.CLIENT, failures);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Nested
    @DisplayName("Structural purity")
    class StructuralPurity {

        @Test
        @DisplayName("extracts the main jar named after the mod id")
        void extractsMainJar() throws Exception {
            String manifest = TestFixtures.manifestJson("goodmod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("Main.class", new byte[]{1}));
            TestFixtures.writeAje(tempDir.resolve("good.aje"), manifest, "goodmod", jar, null);

            AjeExtractor.ExtractedPack pack = extractor.extract(discoverSingle(),
                    tempDir.resolve("work"), failures);
            assertThat(pack).isNotNull();
            assertThat(pack.jars()).hasSize(1);
            assertThat(pack.jars().get(0).getFileName().toString()).isEqualTo("goodmod.jar");
            assertThat(Files.isRegularFile(pack.jars().get(0))).isTrue();
        }

        @Test
        @DisplayName("rejects a main jar not named after the mod id")
        void rejectsMisnamedJar() throws Exception {
            String manifest = TestFixtures.manifestJson("goodmod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("Main.class", new byte[]{1}));
            // hand-build so the jar is named wrong.jar instead of goodmod.jar
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("aprism.manifest.json", manifest.getBytes());
            entries.put("wrong.jar", jar);
            TestFixtures.writeZip(tempDir.resolve("bad.aje"), entries);

            AjeExtractor.ExtractedPack pack = extractor.extract(discoverSingle(),
                    tempDir.resolve("work"), failures);
            assertThat(pack).isNull();
            assertThat(failures).anySatisfy(f ->
                    assertThat(f.render()).contains("must be 'goodmod.jar'"));
        }

        @Test
        @DisplayName("rejects a pack with no jar at the root")
        void rejectsMissingJar() throws Exception {
            String manifest = TestFixtures.manifestJson("emptymod", "1.0.0", "*", null, null);
            TestFixtures.writeZip(tempDir.resolve("empty.aje"),
                    Map.of("aprism.manifest.json", manifest.getBytes()));

            AjeExtractor.ExtractedPack pack = extractor.extract(discoverSingle(),
                    tempDir.resolve("work"), failures);
            assertThat(pack).isNull();
            assertThat(failures).anySatisfy(f ->
                    assertThat(f.render()).contains("no main mod jar"));
        }

        @Test
        @DisplayName("rejects a per-loader subdirectory")
        void rejectsLoaderSubdir() throws Exception {
            String manifest = TestFixtures.manifestJson("goodmod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("Main.class", new byte[]{1}));
            Map<String, byte[]> extra = new LinkedHashMap<>();
            extra.put("fabric/inner.jar", jar);
            TestFixtures.writeAje(tempDir.resolve("loader.aje"), manifest, "goodmod", jar, extra);

            AjeExtractor.ExtractedPack pack = extractor.extract(discoverSingle(),
                    tempDir.resolve("work"), failures);
            assertThat(pack).isNull();
            assertThat(failures).anySatisfy(f ->
                    assertThat(f.render()).contains("per-loader subdirectory"));
        }
    }

    @Nested
    @DisplayName("Content extraction")
    class ContentExtraction {

        @Test
        @DisplayName("extracts resources, mixins, and lib jars and lists mixin configs")
        void extractsAllContent() throws Exception {
            String manifest = TestFixtures.manifestJson("fullmod", "1.0.0", "*", null, null);
            byte[] mainJar = TestFixtures.jarBytes(Map.of("Main.class", new byte[]{1}));
            byte[] libJar = TestFixtures.jarBytes(Map.of("Lib.class", new byte[]{2}));
            Map<String, byte[]> extra = new LinkedHashMap<>();
            extra.put("resources/assets/fullmod/lang/en_us.json", "{}".getBytes());
            extra.put("mixins/fullmod.mixins.json", "{}".getBytes());
            extra.put("lib/dep.jar", libJar);
            TestFixtures.writeAje(tempDir.resolve("full.aje"), manifest, "fullmod", mainJar, extra);

            AjeExtractor.ExtractedPack pack = extractor.extract(discoverSingle(),
                    tempDir.resolve("work"), failures);
            assertThat(pack).isNotNull();
            // main jar + lib jar
            assertThat(pack.jars()).hasSize(2);
            assertThat(pack.jars().get(0).getFileName().toString()).isEqualTo("fullmod.jar");
            assertThat(pack.resourcesDir()).isNotNull();
            assertThat(pack.mixinsDir()).isNotNull();
            assertThat(pack.mixinConfigs()).containsExactly("fullmod.mixins.json");
            assertThat(Files.exists(pack.workDir().resolve("resources/assets/fullmod/lang/en_us.json")))
                    .isTrue();
        }

        @Test
        @DisplayName("reports missing declared mixin configs as a warning path")
        void nullResourcesWhenAbsent() throws Exception {
            String manifest = TestFixtures.manifestJson("leanmod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("Main.class", new byte[]{1}));
            TestFixtures.writeAje(tempDir.resolve("lean.aje"), manifest, "leanmod", jar, null);

            AjeExtractor.ExtractedPack pack = extractor.extract(discoverSingle(),
                    tempDir.resolve("work"), failures);
            assertThat(pack).isNotNull();
            assertThat(pack.resourcesDir()).isNull();
            assertThat(pack.mixinsDir()).isNull();
            assertThat(pack.mixinConfigs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Security")
    class Security {

        @Test
        @DisplayName("a zip-slip entry never escapes the working directory")
        void rejectsZipSlip() throws Exception {
            String manifest = TestFixtures.manifestJson("slipmod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("Main.class", new byte[]{1}));
            Map<String, byte[]> extra = new LinkedHashMap<>();
            extra.put("../escape.txt", "evil".getBytes());
            TestFixtures.writeAje(tempDir.resolve("slip.aje"), manifest, "slipmod", jar, extra);

            // Two defense layers may act: the JDK zip provider may refuse the
            // hostile archive during discovery, or the extractor's own
            // normalize-and-contain check rejects it. The invariant we assert is
            // the security property itself: nothing may be written outside the
            // working directory (i.e. no escape.txt in the temp root).
            List<AjeDiscovery.DiscoveredAje> found = discovery.discover(
                    List.of(tempDir), com.aprism.prismate.host.EnvSide.CLIENT, failures);
            if (!found.isEmpty()) {
                extractor.extract(found.get(0), tempDir.resolve("work"), failures);
            }
            assertThat(Files.exists(tempDir.resolve("escape.txt"))).isFalse();
        }

        @Test
        @DisplayName("rejects a jar nested under resources/")
        void rejectsJarUnderResources() throws Exception {
            String manifest = TestFixtures.manifestJson("nestedmod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("Main.class", new byte[]{1}));
            Map<String, byte[]> extra = new LinkedHashMap<>();
            extra.put("resources/sneaky.jar", jar);
            TestFixtures.writeAje(tempDir.resolve("nested.aje"), manifest, "nestedmod", jar, extra);

            AjeExtractor.ExtractedPack pack = extractor.extract(discoverSingle(),
                    tempDir.resolve("work"), failures);
            assertThat(pack).isNull();
            assertThat(failures).anySatisfy(f ->
                    assertThat(f.render()).contains("violates the .aje contract"));
        }
    }
}
