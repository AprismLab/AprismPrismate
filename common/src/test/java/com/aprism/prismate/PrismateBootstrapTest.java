package com.aprism.prismate;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.prismate.config.PrismateConfig;
import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.testsupport.FakeHostBridge;
import com.aprism.prismate.testsupport.PhaseRecorder;
import com.aprism.prismate.testsupport.TestFixtures;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PrismateBootstrap}: the agent conflict guard, the config
 * disable switch, and the full orchestration order.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateBootstrap")
class PrismateBootstrapTest {

    @TempDir
    Path tempDir;

    private Path modsDir;
    private FakeHostBridge bridge;

    @BeforeEach
    void setUp() throws Exception {
        modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);
        bridge = new FakeHostBridge(tempDir, true, EnvSide.CLIENT);
        PhaseRecorder.clear();
    }

    @Test
    @DisplayName("refuses to boot when the agent property is set")
    void refusesWhenAgentPresent() {
        System.setProperty(AgentConflictDetector.AGENT_PROPERTY, "true");
        try {
            PrismateBootstrap bootstrap = new PrismateBootstrap(bridge);
            PrismateBootstrap.BootOutcome outcome = bootstrap.bootEarly();
            assertThat(outcome).isEqualTo(PrismateBootstrap.BootOutcome.AGENT_CONFLICT);
            assertThat(bridge.logLines()).anySatisfy(l ->
                    assertThat(l).contains("mutually exclusive"));
        } finally {
            System.clearProperty(AgentConflictDetector.AGENT_PROPERTY);
        }
    }

    @Test
    @DisplayName("does no work when disabled via config")
    void doesNothingWhenDisabled() throws Exception {
        Path configFile = tempDir.resolve(PrismateConfig.CONFIG_RELATIVE_PATH);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "{\"enabled\": false}");

        PrismateBootstrap bootstrap = new PrismateBootstrap(bridge);
        PrismateBootstrap.BootOutcome outcome = bootstrap.bootEarly();
        assertThat(outcome).isEqualTo(PrismateBootstrap.BootOutcome.DISABLED);
        assertThat(bridge.injectedJars()).isEmpty();
    }

    @Test
    @DisplayName("boots a sample mod end to end through the orchestrator")
    void fullOrchestration() throws Exception {
        String className = "com.test.OrchestratedMod";
        String manifest = TestFixtures.manifestJson("orchmod", "1.0.0", "*", className, null);
        byte[] modClass = TestFixtures.generateRecordingMod(className, "orchmod");
        byte[] jar = TestFixtures.jarBytes(Map.of(className.replace('.', '/') + ".class", modClass));
        TestFixtures.writeAje(modsDir.resolve("orchmod.aje"), manifest, "orchmod", jar, null);

        PrismateBootstrap bootstrap = new PrismateBootstrap(bridge);
        assertThat(bootstrap.bootEarly()).isEqualTo(PrismateBootstrap.BootOutcome.OK);
        bootstrap.dispatchEarlyLifecycle();
        bootstrap.dispatchSide();
        bootstrap.dispatchComplete();
        bootstrap.logReport();

        assertThat(PhaseRecorder.events()).contains(
                "orchmod:PREINIT", "orchmod:INIT", "orchmod:SETUP", "orchmod:COMPLETE");
        assertThat(bridge.logLines()).anySatisfy(l ->
                assertThat(l).contains("Load Report"));
        bootstrap.shutdown();
    }

    @Test
    @DisplayName("returns OK with zero mods on an empty mods directory")
    void emptyModsDirIsOk() {
        PrismateBootstrap bootstrap = new PrismateBootstrap(bridge);
        assertThat(bootstrap.bootEarly()).isEqualTo(PrismateBootstrap.BootOutcome.OK);
        assertThat(bootstrap.getRuntime().getMods()).isEmpty();
        bootstrap.shutdown();
    }
}
