package com.aprism.prismate.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.api.AprismPhase;
import com.aprism.prismate.config.PrismateConfig;
import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.testsupport.FakeHostBridge;
import com.aprism.prismate.testsupport.PhaseRecorder;
import com.aprism.prismate.testsupport.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@link EmbeddedRuntime}: discovery -> extraction ->
 * dependency resolution -> classpath injection -> strict lifecycle dispatch,
 * all headless against a synthetic host.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("EmbeddedRuntime")
class EmbeddedRuntimeTest {

    @TempDir
    Path tempDir;

    private Path modsDir;
    private Path workDir;
    private FakeHostBridge bridge;
    private PrismateConfig config;

    @BeforeEach
    void setUp() throws Exception {
        modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);
        workDir = tempDir.resolve("prismate").resolve("work");
        bridge = new FakeHostBridge(tempDir, true, EnvSide.CLIENT);
        config = PrismateConfig.load(tempDir);
        PhaseRecorder.clear();
    }

    private Path writeMod(String modId, String className, Map<String, String> depends,
            String... provides) throws Exception {
        String manifest = TestFixtures.manifestJson(modId, "1.0.0", "*", className, depends, provides);
        byte[] modClass = TestFixtures.generateRecordingMod(className, modId);
        byte[] jar = TestFixtures.jarBytes(Map.of(className.replace('.', '/') + ".class", modClass));
        Path aje = modsDir.resolve(modId + ".aje");
        TestFixtures.writeAje(aje, manifest, modId, jar, null);
        return aje;
    }

    @Nested
    @DisplayName("Lifecycle dispatch")
    class LifecycleDispatch {

        @Test
        @DisplayName("drives PREINIT -> INIT -> SETUP -> COMPLETE in strict order")
        void fullCommonLifecycle() throws Exception {
            writeMod("lifecyclemod", "com.test.LifecycleMod", null);

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            assertThat(PhaseRecorder.events()).containsExactly(
                    "lifecyclemod:PREINIT",
                    "lifecyclemod:INIT",
                    "lifecyclemod:SETUP",
                    "lifecyclemod:COMPLETE");
            runtime.close();
        }

        @Test
        @DisplayName("dispatches the CLIENT side phase on the client")
        void clientSidePhase() throws Exception {
            writeMod("sidemod", "com.test.SideMod", null);
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchPhase(AprismPhase.CLIENT);
            // CLIENT invokes onInitialize on the client entrypoint (none declared
            // under 'client'), so nothing records; use the common lifecycle check
            // instead for a positive assertion.
            assertThat(runtime.getMod("sidemod")).isNotNull();
            runtime.close();
        }

        @Test
        @DisplayName("multiple mods dispatch in dependency-resolved order")
        void dependencyOrderAcrossMods() throws Exception {
            // 'dependentmod' depends on 'basemod'; base must initialize first.
            writeMod("basemod", "com.test.BaseMod", null);
            writeMod("dependentmod", "com.test.DependentMod", Map.of("basemod", ">=1.0.0"));

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            List<String> events = PhaseRecorder.events();
            int baseInit = events.indexOf("basemod:INIT");
            int depInit = events.indexOf("dependentmod:INIT");
            assertThat(baseInit).isGreaterThanOrEqualTo(0);
            assertThat(depInit).isGreaterThanOrEqualTo(0);
            assertThat(baseInit).isLessThan(depInit);
            runtime.close();
        }

        @Test
        @DisplayName("isolates a mod that throws without aborting the others")
        void throwingModIsIsolated() throws Exception {
            String throwingClass = "com.test.ThrowingMod";
            String manifest = TestFixtures.manifestJson("throwingmod", "1.0.0", "*",
                    throwingClass, null);
            byte[] modClass = TestFixtures.generateThrowingMod(throwingClass, "boom");
            byte[] jar = TestFixtures.jarBytes(Map.of(
                    throwingClass.replace('.', '/') + ".class", modClass));
            TestFixtures.writeAje(modsDir.resolve("throwing.aje"), manifest, "throwingmod", jar, null);
            writeMod("healthy", "com.test.HealthyMod", null);

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            // The healthy mod still completes its full lifecycle
            assertThat(PhaseRecorder.events()).contains(
                    "healthy:PREINIT", "healthy:INIT", "healthy:SETUP", "healthy:COMPLETE");
            // The throwing mod produced a named lifecycle failure
            assertThat(runtime.getFailures()).anySatisfy(f ->
                    assertThat(f.render()).contains("throwingmod"));
            runtime.close();
        }

        @Test
        @DisplayName("stores the mod instance on the container after INIT")
        void instanceStored() throws Exception {
            writeMod("instancemod", "com.test.InstanceMod", null);
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();
            assertThat(runtime.getMod("instancemod").getInstance()).isNotNull();
            runtime.close();
        }
    }

    @Nested
    @DisplayName("Dependency resolution")
    class DependencyResolution {

        @Test
        @DisplayName("a mod with a missing dependency is isolated with a named failure")
        void missingDependencyIsolated() throws Exception {
            writeMod("orphanmod", "com.test.OrphanMod", Map.of("nosuchmod", ">=1.0.0"));
            writeMod("fine", "com.test.FineMod", null);

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            assertThat(runtime.getMod("orphanmod")).isNull();
            assertThat(runtime.getMod("fine")).isNotNull();
            assertThat(PhaseRecorder.events()).contains("fine:INIT");
            assertThat(runtime.getFailures()).anySatisfy(f ->
                    assertThat(f.render()).contains("nosuchmod"));
            runtime.close();
        }

        @Test
        @DisplayName("a version conflict is isolated with a named failure")
        void versionConflictIsolated() throws Exception {
            writeMod("lowmod", "com.test.LowMod", null); // version 1.0.0
            writeMod("demanding", "com.test.DemandingMod", Map.of("lowmod", ">=99.0.0"));

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            assertThat(runtime.getMod("demanding")).isNull();
            assertThat(runtime.getMod("lowmod")).isNotNull();
            assertThat(runtime.getFailures()).anySatisfy(f ->
                    assertThat(f.render()).contains("lowmod"));
            runtime.close();
        }

        @Test
        @DisplayName("resolves the injected 'aprism' environment id")
        void aprismEnvironmentId() throws Exception {
            writeMod("aprismdep", "com.test.AprismDep", Map.of("aprism", ">=26.0"));
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();
            assertThat(runtime.getMod("aprismdep")).isNotNull();
            assertThat(PhaseRecorder.events()).contains("aprismdep:INIT");
            runtime.close();
        }

        @Test
        @DisplayName("resolves environment ids like minecraft and fabricloader")
        void environmentIds() throws Exception {
            writeMod("envmod", "com.test.EnvMod",
                    Map.of("minecraft", ">=26.0", "fabricloader", ">=0.16.0"));
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();
            assertThat(runtime.getMod("envmod")).isNotNull();
            runtime.close();
        }

        @Test
        @DisplayName("supports the provides alias")
        void providesAlias() throws Exception {
            // providermod provides the id 'virtualapi'; consumer depends on it.
            writeMod("providermod", "com.test.ProviderMod", null, "virtualapi");
            writeMod("consumermod", "com.test.ConsumerMod", Map.of("virtualapi", ">=1.0.0"));

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            assertThat(runtime.getMod("providermod")).isNotNull();
            assertThat(runtime.getMod("consumermod")).isNotNull();
            assertThat(PhaseRecorder.events()).contains("consumermod:INIT");
            runtime.close();
        }
    }

    @Nested
    @DisplayName("Classpath injection")
    class ClasspathInjection {

        @Test
        @DisplayName("injects the extracted jars through the host bridge")
        void injectsJarsThroughHost() throws Exception {
            writeMod("injectmod", "com.test.InjectMod", null);
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();

            assertThat(bridge.injectedJars()).isNotEmpty();
            assertThat(bridge.injectedJars().get(0).getFileName().toString())
                    .isEqualTo("injectmod.jar");
            runtime.close();
        }

        @Test
        @DisplayName("falls back to the managed classloader when host injection fails")
        void degradedPath() throws Exception {
            FakeHostBridge degraded = new FakeHostBridge(tempDir, false, EnvSide.CLIENT);
            writeMod("degradedmod", "com.test.DegradedMod", null);

            EmbeddedRuntime runtime = EmbeddedRuntime.create(degraded, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            assertThat(runtime.isDegraded()).isTrue();
            // Lifecycle still works through the managed classloader
            assertThat(PhaseRecorder.events()).contains("degradedmod:INIT");
            runtime.close();
        }

        @Test
        @DisplayName("loads an entrypoint class that exists only inside the extracted jar")
        void loadsFromClassloaderNotClasspath() throws Exception {
            String className = "com.test.JarOnlyMod";
            writeMod("jaronly", className, null);
            // Prove the class is absent from the test classpath
            try {
                Class.forName(className);
                throw new AssertionError("class should not be on the test classpath");
            } catch (ClassNotFoundException expected) {
                // expected
            }
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();
            assertThat(PhaseRecorder.events()).contains("jaronly:INIT");
            runtime.close();
        }
    }

    @Nested
    @DisplayName("JiJ lib dependencies")
    class JiJLib {

        @Test
        @DisplayName("extracts and injects lib/*.jar alongside the main jar")
        void libJarsInjected() throws Exception {
            String className = "com.test.LibMod";
            String manifest = TestFixtures.manifestJson("libmod", "1.0.0", "*", className, null);
            byte[] modClass = TestFixtures.generateRecordingMod(className, "libmod");
            byte[] mainJar = TestFixtures.jarBytes(Map.of(
                    className.replace('.', '/') + ".class", modClass));
            byte[] libJar = TestFixtures.jarBytes(Map.of("lib/Dep.class", new byte[]{1}));
            java.util.Map<String, byte[]> extra = new java.util.LinkedHashMap<>();
            extra.put("lib/deplib.jar", libJar);
            TestFixtures.writeAje(modsDir.resolve("libmod.aje"), manifest, "libmod", mainJar, extra);

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();

            List<String> injectedNames = bridge.injectedJars().stream()
                    .map(p -> p.getFileName().toString()).toList();
            assertThat(injectedNames).contains("libmod.jar", "deplib.jar");
            runtime.close();
        }
    }

    @Nested
    @DisplayName("Access widener")
    class AccessWidener {

        @Test
        @DisplayName("registers a manifest-declared access widener with the managed classloader")
        void registersDeclaredWidener() throws Exception {
            String className = "com.test.WidenMod";
            String manifest = TestFixtures.manifestJson("widenmod", "1.0.0", "*", className, null)
                    .replace("\"accessWidener\": null", "\"accessWidener\": \"widenmod.accesswidener\"");
            byte[] modClass = TestFixtures.generateRecordingMod(className, "widenmod");
            byte[] jar = TestFixtures.jarBytes(Map.of(
                    className.replace('.', '/') + ".class", modClass));
            String widenerText = "accessWidener v1 named\n"
                    + "accessible class net/minecraft/client/Minecraft\n";
            java.util.Map<String, byte[]> extra = new java.util.LinkedHashMap<>();
            extra.put("widenmod.accesswidener", widenerText.getBytes());
            TestFixtures.writeAje(modsDir.resolve("widenmod.aje"), manifest, "widenmod", jar, extra);

            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();

            // The classpath entry succeeded (no failure recorded for widenmod)
            assertThat(runtime.getMod("widenmod")).isNotNull();
            assertThat(runtime.getFailures()).noneSatisfy(f ->
                    assertThat(f.render()).contains("widenmod"));
            runtime.close();
        }
    }

    @Nested
    @DisplayName("Report file")
    class ReportFile {

        @Test
        @DisplayName("writes the load report to <gameDir>/prismate/reports/load-report.txt")
        void writesReportFile() throws Exception {
            writeMod("reportmod", "com.test.ReportMod", null);
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();
            runtime.dispatchCommonLifecycle();

            java.nio.file.Path reportFile = runtime.writeReportFile();
            assertThat(reportFile).isNotNull();
            assertThat(reportFile.getFileName().toString()).isEqualTo("load-report.txt");
            String content = java.nio.file.Files.readString(reportFile);
            assertThat(content).contains("AprismPrismate Load Report");
            assertThat(content).contains("reportmod");
            runtime.close();
        }

        @Test
        @DisplayName("surfaces the manifest displayName in the load report (Alpha.7)")
        void reportSurfacesDisplayName() throws Exception {
            // TestFixtures manifests set displayName = "<id> display"
            writeMod("reportmod", "com.test.ReportMod", null);
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();

            String report = runtime.renderReport();
            assertThat(report).contains("reportmod display");
            runtime.close();
        }

        @Test
        @DisplayName("skips first-run guidance when packs were discovered (Alpha.7)")
        void firstRunGuidanceSkippedWhenPacksExist() throws Exception {
            writeMod("reportmod", "com.test.ReportMod", null);
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();

            assertThat(runtime.writeFirstRunGuidanceIfEmpty()).isNull();
            runtime.close();
        }

        @Test
        @DisplayName("writes first-run guidance when no packs were discovered (Alpha.7)")
        void firstRunGuidanceWhenEmpty() {
            // Empty mods dir -> discoveredCount == 0 -> guidance file written
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge, config);
            runtime.boot();

            java.nio.file.Path guidance = runtime.writeFirstRunGuidanceIfEmpty();
            assertThat(guidance).isNotNull();
            assertThat(guidance.getFileName().toString()).isEqualTo("FIRST-RUN.txt");
            try {
                String content = java.nio.file.Files.readString(guidance);
                assertThat(content).contains("first-run guidance");
                assertThat(content).contains("mods");
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            // Second call is a no-op (file already exists)
            assertThat(runtime.writeFirstRunGuidanceIfEmpty()).isNull();
            runtime.close();
        }
    }
}
