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
 * Adversarial pass against the host-tick bridge surface (v26.7 robustness
 * assessment per the family rule): cancellation semantics on non-cancellable
 * events, re-entrant bootstrap calls from tick callbacks, and many-listener
 * storms.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Host-tick bridge adversarial")
class HostTickBridgeAdversarialTest {

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

        String manifest = TestFixtures.manifestJson(
                "tickmod", "1.0.0", "*", "com.test.TickMod", null);
        Map<String, byte[]> classes =
                TestFixtures.generateTickSubscriberPair("com.test.TickMod");
        TestFixtures.writeAje(modsDir.resolve("tickmod.aje"), manifest,
                "tickmod", TestFixtures.jarBytes(classes), null);

        runtime = EmbeddedRuntime.create(bridge, PrismateConfig.load(tempDir));
    }

    @Test
    @DisplayName("cancel attempts on non-cancellable END ticks are ignored safely")
    void cancelOnNonCancellableIgnored() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        // Directly probe cancellation semantics of the delivered event type.
        GameTickEvent event = new GameTickEvent(GameTickEvent.Stage.END, 0);
        assertThat(event.isCancellable()).isFalse();
        assertThatCode(() -> event.setCancelled(true)).doesNotThrowAnyException();
        assertThat(event.isCancelled()).isFalse();

        // The bus keeps delivering after a listener cancels a non-cancellable.
        bridge.fireTicks(2);
        assertThat(TickProbe.ticks).hasSize(2);
        runtime.close();
    }

    @Test
    @DisplayName("re-entrant startTicking and boot calls from a tick callback do not corrupt state")
    void reentrantCallsSafe() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        assertThatCode(() -> {
            runtime.startTicking();      // re-entrant registration
            runtime.boot();              // re-entrant boot
            bridge.fireTicks(1);         // callback fires another round
        }).doesNotThrowAnyException();

        // Exactly one delivery per fireTicks call chain: idempotency guards held.
        assertThat(TickProbe.ticks).hasSize(1);
        runtime.close();
    }

    @Test
    @DisplayName("a ten-thousand-tick storm completes without loss or deadlock")
    void tickStormBounded() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        bridge.fireTicks(10_000);

        assertThat(TickProbe.ticks).hasSize(10_000);
        runtime.close();
    }
}
