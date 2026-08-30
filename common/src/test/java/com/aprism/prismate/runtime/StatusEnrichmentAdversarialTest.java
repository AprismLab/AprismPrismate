package com.aprism.prismate.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Adversarial pass for the v26.14 status enrichment (family rule): concurrent
 * publish paths racing on the enrichment sampler, and integer-second rate
 * edge behavior.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Status enrichment v26.14 adversarial")
class StatusEnrichmentAdversarialTest {

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
    @DisplayName("concurrent tick delivery and status refresh do not corrupt counters")
    void concurrentDeliveryAndRefresh() throws Exception {
        runtime.boot();
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.startTicking();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // Thread A: drives ticks in bursts.
        pool.submit(() -> {
            try {
                start.await();
                for (int burst = 0; burst < 20; burst++) {
                    bridge.fireTicks(50);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        // Thread B: hammers the refresh gate (shouldRefreshStatus is a pure
        // read; markStatusRefreshed only moves the checkpoint forward).
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 500; i++) {
                    if (runtime.shouldRefreshStatus()) {
                        runtime.markStatusRefreshed();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Total delivered is exact regardless of interleaving: the delivered
        // counter is driven by fireTicks only, and checkpoint moves are
        // monotonic so no tick is double-counted or lost.
        assertThat(runtime.getDeliveredTickCount()).isEqualTo(1000);
        runtime.close();
    }

    @Test
    @DisplayName("sub-second refresh interval omits the rate without crashing")
    void subSecondIntervalSafe() {
        runtime.boot();
        // No ticks: elapsed interval would be 0 seconds; the sampler must
        // simply not expose a rate for the first publish.
        assertThatCode(() -> {
            runtime.startTicking();
            runtime.markStatusRefreshed();
        }).doesNotThrowAnyException();
        runtime.close();
    }
}
