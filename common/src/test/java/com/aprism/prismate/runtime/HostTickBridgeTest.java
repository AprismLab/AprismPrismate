package com.aprism.prismate.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.api.AprismPhase;
import com.aprism.api.gameevent.GameTickEvent;
import com.aprism.prismate.config.PrismateConfig;
import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.testsupport.FakeHostBridge;
import com.aprism.prismate.testsupport.TestFixtures;
import com.aprism.prismate.testsupport.TickProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for the host-tick bridge (v26.7-Alpha.3): host ticks become
 * GameTickEvent deliveries on the shared bus, fail-safely.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Host-tick bridge")
class HostTickBridgeTest {

    @TempDir
    Path tempDir;

    private FakeHostBridge bridge;
    private EmbeddedRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        TickProbe.clear();
        Path modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);
        bridge = new FakeHostBridge(tempDir, true, EnvSide.CLIENT);

        // A mod that subscribes to GameTickEvent during INIT.
        String manifest = TestFixtures.manifestJson(
                "tickmod", "1.0.0", "*", "com.test.TickMod", null);
        Map<String, byte[]> classes =
                TestFixtures.generateTickSubscriberPair("com.test.TickMod");
        byte[] jar = TestFixtures.jarBytes(classes);
        TestFixtures.writeAje(modsDir.resolve("tickmod.aje"), manifest,
                "tickmod", jar, null);

        runtime = EmbeddedRuntime.create(bridge, PrismateConfig.load(tempDir));
    }

    @Test
    @DisplayName("host ticks are delivered as GameTickEvents after INIT")
    void ticksDelivered() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT); // subscriber registers here
        assertThat(runtime.startTicking()).isTrue();

        bridge.fireTicks(3);

        assertThat(TickProbe.ticks).hasSize(3);
        assertThat(TickProbe.ticks).containsExactly(
                GameTickEvent.Stage.END,
                GameTickEvent.Stage.END,
                GameTickEvent.Stage.END);
        runtime.close();
    }

    @Test
    @DisplayName("a throwing tick listener is isolated and ticking continues")
    void throwingListenerIsolated() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        assertThat(runtime.startTicking()).isTrue();

        TickProbe.throwOnNext = true;
        assertThatCode(() -> bridge.fireTicks(2)).doesNotThrowAnyException();
        assertThat(TickProbe.ticks).hasSize(1); // first threw, second delivered
        runtime.close();
    }

    @Test
    @DisplayName("re-registration replaces the hook; deliveries stay consistent")
    void reRegistrationIsHarmless() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        assertThat(runtime.startTicking()).isTrue();
        assertThat(runtime.startTicking()).isTrue();

        bridge.fireTicks(1);
        assertThat(TickProbe.ticks).hasSize(1);
        runtime.close();
    }

    @Test
    @DisplayName("ticks before any subscriber are simply not observed")
    void noSubscriberNoDelivery() throws Exception {
        runtime.boot(); // INIT not dispatched: nobody subscribed
        assertThat(runtime.startTicking()).isTrue();

        bridge.fireTicks(5);
        assertThat(TickProbe.ticks).isEmpty(); // bus had no listeners; no error
        runtime.close();
    }
}
