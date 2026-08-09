package com.aprism.prismate.runtime;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.testsupport.FakeHostBridge;
import com.aprism.prismate.testsupport.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the v26.1-Alpha.4 classloader-level resource injection: extracted
 * {@code resources/} directories are registered with the Prismate-managed
 * mod classloader so mods loaded through it can resolve their own resource
 * entries — closing the NeoForge resource gap where the host offers no
 * runtime resource-injection path (docs 01 Section 13 issue 2).
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Classloader-level resource injection")
class ClassloaderResourceInjectionTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("addResourceDir makes resource entries resolvable through the loader")
    void addResourceDirMakesResourcesResolvable() throws Exception {
        Path resourcesDir = tempDir.resolve("resources");
        Path langFile = resourcesDir.resolve("assets/prismatesmoke/lang/en_us.json");
        Files.createDirectories(langFile.getParent());
        Files.writeString(langFile, "{\"key\":\"value\"}", StandardCharsets.UTF_8);

        try (PrismateModClassLoader loader =
                new PrismateModClassLoader(getClass().getClassLoader())) {
            loader.addResourceDir(resourcesDir);
            try (InputStream in = loader.getResourceAsStream(
                    "assets/prismatesmoke/lang/en_us.json")) {
                assertThat(in).isNotNull();
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("value");
            }
        }
    }

    @Test
    @DisplayName("EmbeddedRuntime registers pack resources/ into the managed loader")
    void runtimeRegistersResourcesIntoManagedLoader() throws Exception {
        // A pack whose manifest carries no deps and whose jar holds a simple
        // recording mod, plus a resources/ dir with a known entry.
        String className = "com.test.resmod.ResMod";
        String manifest = TestFixtures.manifestJson("resmod", "1.0.0", "*", className, null);
        byte[] modClass = TestFixtures.generateRecordingMod(className, "resmod");
        byte[] jar = TestFixtures.jarBytes(
                Map.of(className.replace('.', '/') + ".class", modClass));
        Path modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);
        TestFixtures.writeAje(modsDir.resolve("resmod.aje"), manifest, "resmod", jar,
                Map.of("resources/assets/resmod/data.json",
                        "{\"res\":\"present\"}".getBytes(StandardCharsets.UTF_8)));

        FakeHostBridge bridge = new FakeHostBridge(tempDir, false, EnvSide.CLIENT);
        EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge,
                com.aprism.prismate.config.PrismateConfig.load(tempDir));
        runtime.boot();

        // The mod loaded through the managed loader must be able to resolve
        // the resource entry that lives in its extracted resources/ dir.
        ClassLoader packLoader = runtime.classLoaderForPacks();
        try (InputStream in = packLoader.getResourceAsStream("assets/resmod/data.json")) {
            assertThat(in).isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("present");
        }
        runtime.close();
    }
}
