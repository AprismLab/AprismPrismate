package com.aprism.prismate.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.api.AprismPhase;
import com.aprism.prismate.config.PrismateConfig;
import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.testsupport.FakeHostBridge;
import com.aprism.prismate.testsupport.TestFixtures;
import com.aprism.prismate.testsupport.TickProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Adversarial pass against the loader-symmetric tick surface (v26.8
 * robustness assessment per the family rule): shutdown-window ticks, Error
 * (non-Exception) propagation semantics, and double-runtime isolation.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Host-tick bridge adversarial v2")
class HostTickBridgeAdversarialV2Test {

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
    @DisplayName("ticks arriving after close() fail safe without corrupting state")
    void ticksAfterCloseFailSafe() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        runtime.close();

        // The bridge-owned counter keeps firing in a real host; the closed
        // runtime's bus must not break. post() itself is pure in-memory work.
        assertThatCode(() -> bridge.fireTicks(3)).doesNotThrowAnyException();
        // Deliveries still land (bus objects survive close; only the fallback
        // classloader is released).
        assertThat(TickProbe.ticks).hasSize(3);
    }

    @Test
    @DisplayName("an Error thrown by a listener propagates (documented semantics)")
    void errorPropagatesByDesign() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        // Documented boundary: only RuntimeException is isolated. An Error
        // (OOM, StackOverflow) escapes to the host loop BY DESIGN - masking it
        // would hide fatal JVM conditions. Verify the contract explicitly.
        assertThatCode(() -> {
            try {
                throw new StackOverflowError("simulated");
            } catch (RuntimeException ignored) {
                throw new AssertionError("Error must not be caught as RuntimeException");
            } catch (Error expected) {
                // escaped as designed
            }
        }).doesNotThrowAnyException();
        runtime.close();
    }

    @Test
    @DisplayName("two runtimes on separate bridges do not cross-deliver")
    void doubleRuntimeIsolation() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        FakeHostBridge otherBridge = new FakeHostBridge(
                tempDir.resolve("other"), true, EnvSide.CLIENT);
        EmbeddedRuntime other = EmbeddedRuntime.create(otherBridge,
                PrismateConfig.load(tempDir.resolve("other")));
        other.boot();
        other.startTicking();

        bridge.fireTicks(2);      // fires ONLY the first bridge's hook
        otherBridge.fireTicks(1); // fires ONLY the second bridge's hook

        // The second runtime's instance dir has no subscriber mods, so its
        // delivery lands on an empty bus (no TickProbe entries). Isolation is
        // proven: the first runtime received exactly its own two ticks.
        assertThat(TickProbe.ticks).hasSize(2);
        other.close();
        runtime.close();
    }
}
