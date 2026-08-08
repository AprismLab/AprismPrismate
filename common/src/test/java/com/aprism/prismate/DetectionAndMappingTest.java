package com.aprism.prismate;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.api.AprismPhase;
import com.aprism.prismate.host.EnvSide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentConflictDetector} and {@link LifecycleMapper}.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Detection and lifecycle mapping")
class DetectionAndMappingTest {

    @TempDir
    Path tempDir;

    @Nested
    class AgentDetection {
        @Test
        @DisplayName("reports no agent when the marker is absent")
        void noAgentByDefault() {
            // In the test JVM the Aprism agent is not attached
            assertThat(AgentConflictDetector.isAprismAgentPresent()).isFalse();
        }

        @Test
        @DisplayName("honors the forward-compatible system property")
        void honorsAgentProperty() {
            System.setProperty(AgentConflictDetector.AGENT_PROPERTY, "true");
            try {
                assertThat(AgentConflictDetector.isAprismAgentPresent()).isTrue();
            } finally {
                System.clearProperty(AgentConflictDetector.AGENT_PROPERTY);
            }
        }

        @Test
        @DisplayName("produces a readable refusal message naming both options")
        void refusalMessageIsReadable() {
            String msg = AgentConflictDetector.refusalMessage();
            assertThat(msg).containsIgnoringCase("aprism");
            assertThat(msg).containsIgnoringCase("mutually exclusive");
        }
    }

    @Nested
    class LifecycleMapping {
        @Test
        @DisplayName("common phases are ordered PREINIT, INIT, SETUP, COMPLETE")
        void commonPhaseOrder() {
            assertThat(LifecycleMapper.commonPhases()).containsExactly(
                    AprismPhase.PREINIT, AprismPhase.INIT,
                    AprismPhase.SETUP, AprismPhase.COMPLETE);
        }

        @Test
        @DisplayName("maps CLIENT side to CLIENT phase and DEDICATED_SERVER to SERVER")
        void sidePhaseMapping() {
            assertThat(LifecycleMapper.sidePhase(EnvSide.CLIENT)).isEqualTo(AprismPhase.CLIENT);
            assertThat(LifecycleMapper.sidePhase(EnvSide.DEDICATED_SERVER))
                    .isEqualTo(AprismPhase.SERVER);
        }

        @Test
        @DisplayName("maps phases to entrypoint keys")
        void entrypointKeys() {
            assertThat(LifecycleMapper.entrypointKeyFor(AprismPhase.PREINIT)).isEqualTo("main");
            assertThat(LifecycleMapper.entrypointKeyFor(AprismPhase.INIT)).isEqualTo("main");
            assertThat(LifecycleMapper.entrypointKeyFor(AprismPhase.SETUP)).isEqualTo("main");
            assertThat(LifecycleMapper.entrypointKeyFor(AprismPhase.COMPLETE)).isEqualTo("main");
            assertThat(LifecycleMapper.entrypointKeyFor(AprismPhase.CLIENT)).isEqualTo("client");
            assertThat(LifecycleMapper.entrypointKeyFor(AprismPhase.SERVER)).isEqualTo("server");
        }

        @Test
        @DisplayName("identifies side phases")
        void isSidePhase() {
            assertThat(LifecycleMapper.isSidePhase(AprismPhase.CLIENT)).isTrue();
            assertThat(LifecycleMapper.isSidePhase(AprismPhase.SERVER)).isTrue();
            assertThat(LifecycleMapper.isSidePhase(AprismPhase.INIT)).isFalse();
        }
    }

    @Nested
    class Version {
        @Test
        @DisplayName("normalizes an Aprism version to three segments")
        void normalizes() {
            assertThat(PrismateVersion.normalizeAprismVersion("v26.0-Alpha.8"))
                    .isEqualTo("26.0.0");
            assertThat(PrismateVersion.normalizeAprismVersion("26.1")).isEqualTo("26.1.0");
            assertThat(PrismateVersion.normalizeAprismVersion("26.2.5")).isEqualTo("26.2.5");
            assertThat(PrismateVersion.normalizeAprismVersion(null)).isEqualTo("0.0.0");
            assertThat(PrismateVersion.normalizeAprismVersion("")).isEqualTo("0.0.0");
        }
    }

    @Nested
    class EnvSideMatching {
        @Test
        @DisplayName("COMMON mods run on both sides")
        void commonRunsEverywhere() {
            assertThat(EnvSide.CLIENT.matches(com.aprism.api.Environment.COMMON)).isTrue();
            assertThat(EnvSide.DEDICATED_SERVER.matches(com.aprism.api.Environment.COMMON)).isTrue();
        }

        @Test
        @DisplayName("CLIENT mods run only on the client")
        void clientOnlyClient() {
            assertThat(EnvSide.CLIENT.matches(com.aprism.api.Environment.CLIENT)).isTrue();
            assertThat(EnvSide.DEDICATED_SERVER.matches(com.aprism.api.Environment.CLIENT)).isFalse();
        }

        @Test
        @DisplayName("DEDICATED_SERVER mods run only on the server")
        void serverOnlyServer() {
            assertThat(EnvSide.DEDICATED_SERVER.matches(
                    com.aprism.api.Environment.DEDICATED_SERVER)).isTrue();
            assertThat(EnvSide.CLIENT.matches(
                    com.aprism.api.Environment.DEDICATED_SERVER)).isFalse();
        }
    }
}
