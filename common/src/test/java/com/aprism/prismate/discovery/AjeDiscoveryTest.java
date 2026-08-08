package com.aprism.prismate.discovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.testsupport.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AjeDiscovery}: recursive scanning, manifest parsing,
 * validation, side-environment filtering, and named failures for malformed
 * packs.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("AjeDiscovery")
class AjeDiscoveryTest {

    @TempDir
    Path tempDir;

    private List<LoadFailure> failures = new ArrayList<>();
    private final AjeDiscovery discovery = new AjeDiscovery();

    private Path writePack(String fileName, String modId, String environment) throws Exception {
        String manifest = TestFixtures.manifestJson(modId, "1.0.0", environment, null, null);
        byte[] jar = TestFixtures.jarBytes(Map.of("dummy.txt", "x".getBytes()));
        Path aje = tempDir.resolve(fileName);
        TestFixtures.writeAje(aje, manifest, modId, jar, null);
        return aje;
    }

    @Nested
    @DisplayName("Scanning")
    class Scanning {

        @Test
        @DisplayName("discovers a valid .aje in the scan directory")
        void discoversValidPack() throws Exception {
            writePack("alpha.aje", "alphamod", "*");
            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).manifest().id()).isEqualTo("alphamod");
            assertThat(failures).isEmpty();
        }

        @Test
        @DisplayName("recurses into subdirectories")
        void recursesIntoSubdirectories() throws Exception {
            Path nested = tempDir.resolve("sub/deeper");
            Files.createDirectories(nested);
            String manifest = TestFixtures.manifestJson("betamod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("d.txt", "x".getBytes()));
            TestFixtures.writeAje(nested.resolve("beta.aje"), manifest, "betamod", jar, null);

            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).hasSize(1);
            assertThat(found.get(0).manifest().id()).isEqualTo("betamod");
        }

        @Test
        @DisplayName("skips a missing scan directory without failing")
        void skipsMissingDirectory() {
            List<AjeDiscovery.DiscoveredAje> found = discovery.discover(
                    List.of(tempDir.resolve("nope")), EnvSide.CLIENT, failures);
            assertThat(found).isEmpty();
            assertThat(failures).isEmpty();
        }

        @Test
        @DisplayName("ignores non-.aje files")
        void ignoresNonAjeFiles() throws Exception {
            Files.writeString(tempDir.resolve("readme.txt"), "hi");
            Files.writeString(tempDir.resolve("fake.zip"), "zip");
            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("scans multiple directories and dedupes by path order")
        void scansMultipleDirectories() throws Exception {
            Path other = tempDir.resolve("other");
            Files.createDirectories(other);
            writePack("a.aje", "amod", "*");
            String manifest = TestFixtures.manifestJson("bmod", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("d.txt", "x".getBytes()));
            TestFixtures.writeAje(other.resolve("b.aje"), manifest, "bmod", jar, null);

            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir, other), EnvSide.CLIENT, failures);
            assertThat(found).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Malformed packs")
    class MalformedPacks {

        @Test
        @DisplayName("records a named failure for a pack without a manifest")
        void missingManifest() throws Exception {
            byte[] jar = TestFixtures.jarBytes(Map.of("d.txt", "x".getBytes()));
            TestFixtures.writeZip(tempDir.resolve("broken.aje"), Map.of("whatever.aje", jar));

            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).isEmpty();
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0).render())
                    .contains("aprism.manifest.json");
        }

        @Test
        @DisplayName("records a named failure for an unopenable archive")
        void notARealZip() throws Exception {
            Files.writeString(tempDir.resolve("corrupt.aje"), "this is not a zip");
            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).isEmpty();
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0).stage()).isEqualTo(LoadFailure.DISCOVERY);
        }

        @Test
        @DisplayName("records a named failure for an invalid manifest id")
        void invalidManifestId() throws Exception {
            String manifest = TestFixtures.manifestJson("X", "1.0.0", "*", null, null);
            byte[] jar = TestFixtures.jarBytes(Map.of("d.txt", "x".getBytes()));
            TestFixtures.writeAje(tempDir.resolve("bad.aje"), manifest, "X", jar, null);

            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).isEmpty();
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0).render()).contains("invalid manifest");
        }

        @Test
        @DisplayName("detects duplicate mod ids across packs")
        void duplicateModId() throws Exception {
            writePack("a.aje", "dupmod", "*");
            writePack("b.aje", "dupmod", "*");
            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).hasSize(1);
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0).render()).contains("duplicate mod id");
        }
    }

    @Nested
    @DisplayName("Environment filtering")
    class EnvironmentFiltering {

        @Test
        @DisplayName("skips a client-only pack on the dedicated server")
        void clientOnlyOnServer() throws Exception {
            writePack("clientonly.aje", "clientmod", "client");
            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.DEDICATED_SERVER, failures);
            assertThat(found).isEmpty();
            assertThat(failures).isEmpty(); // normal skip, not a failure
        }

        @Test
        @DisplayName("skips a server-only pack on the client")
        void serverOnlyOnClient() throws Exception {
            writePack("serveronly.aje", "servermod", "dedicated_server");
            List<AjeDiscovery.DiscoveredAje> found =
                    discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures);
            assertThat(found).isEmpty();
            assertThat(failures).isEmpty();
        }

        @Test
        @DisplayName("keeps a common pack on both sides")
        void commonOnBothSides() throws Exception {
            writePack("common.aje", "commonmod", "*");
            assertThat(discovery.discover(List.of(tempDir), EnvSide.CLIENT, failures))
                    .hasSize(1);
            assertThat(discovery.discover(List.of(tempDir), EnvSide.DEDICATED_SERVER, failures))
                    .hasSize(1);
        }
    }
}
