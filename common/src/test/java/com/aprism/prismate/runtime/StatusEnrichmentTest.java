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
import com.aprism.prismate.testsupport.TickProbe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the v26.14 status enrichment: environment fingerprint and live
 * tick-rate sampling across the LOADED/refresh/SHUTDOWN publish cadence.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Status enrichment v26.14")
class StatusEnrichmentTest {

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
        runtime = EmbeddedRuntime.create(bridge, PrismateConfig.load(tempDir));
    }

    @Test
    @DisplayName("deliveredTicks counts strictly across refresh checkpoints")
    void tickCountingMonotonic() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        bridge.fireTicks(1200); // two 600-tick refresh windows

        assertThat(runtime.getDeliveredTickCount()).isEqualTo(1200);
        assertThat(runtime.shouldRefreshStatus()).isTrue();
        runtime.markStatusRefreshed();
        assertThat(runtime.shouldRefreshStatus()).isFalse(); // just refreshed
        bridge.fireTicks(599);
        assertThat(runtime.shouldRefreshStatus()).isFalse(); // 599 < 600
        bridge.fireTicks(1);
        assertThat(runtime.shouldRefreshStatus()).isTrue(); // 600th
        runtime.close();
    }

    @Test
    @DisplayName("shouldRefreshStatus stays false when the tick hook is unavailable")
    void noHookNoRefresh() throws Exception {
        runtime.boot();
        // startTicking never called: tickingAvailable false
        assertThat(runtime.shouldRefreshStatus()).isFalse();
        runtime.close();
    }
}
