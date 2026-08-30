package com.aprism.prismate;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.testsupport.FakeHostBridge;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the v26.15 refresh-failure alert dedup: one boot-scoped warning
 * for persistent failures, reset on success.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Refresh-failure alert dedup")
class RefreshFailureDedupTest {

    @TempDir
    Path tempDir;

    private FakeHostBridge bridge;
    private PrismateBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bridge = new FakeHostBridge(tempDir, false, EnvSide.CLIENT);
        bootstrap = new PrismateBootstrap(bridge);
        // Boot the real pipeline (no .aje packs: empty discovery is fine;
        // runtime is non-null after bootEarly regardless).
        assertThat(bootstrap.bootEarly()).isEqualTo(
                PrismateBootstrap.BootOutcome.OK);
        bootstrap.getRuntime().startTicking();
    }

    @Test
    @DisplayName("persistently-failing refresh logs one warning, not one per interval")
    void failureLoggedOnce() throws Exception {
        // A plain FILE at a bad location makes every publish fail. The game
        // dir itself is what publish() creates into, so point the bridge's
        // gameDir at a file-as-dir.
        Path badGameDir = tempDir.resolve("game");
        Files.writeString(badGameDir, "occupied");
        bridge.setGameDir(badGameDir);

        bootstrap.refreshStatusForTest();
        bootstrap.refreshStatusForTest();
        bootstrap.refreshStatusForTest();

        long failureLines = bridge.logLines().stream()
                .filter(l -> l.contains("Periodic status refresh failed"))
                .count();
        assertThat(failureLines).isEqualTo(1);
    }

    @Test
    @DisplayName("a successful refresh resets the dedup flag")
    void successResetsFlag() throws Exception {
        Path badGameDir = tempDir.resolve("bad");
        Files.writeString(badGameDir, "occupied");
        bridge.setGameDir(badGameDir);
        bootstrap.refreshStatusForTest(); // fails -> flag set

        // Flip to a healthy dir: the next publish succeeds (flag reset), and
        // a subsequent failure after another flip warns again (2 total).
        Path goodDir = tempDir.resolve("good");
        Files.createDirectories(goodDir);
        bridge.setGameDir(goodDir);
        bootstrap.refreshStatusForTest(); // succeeds -> flag reset

        Path badAgain = tempDir.resolve("bad2");
        Files.writeString(badAgain, "occupied");
        bridge.setGameDir(badAgain);
        bootstrap.refreshStatusForTest(); // fails -> warns again

        long failureLines = bridge.logLines().stream()
                .filter(l -> l.contains("Periodic status refresh failed"))
                .count();
        assertThat(failureLines).isEqualTo(2);
    }
}

