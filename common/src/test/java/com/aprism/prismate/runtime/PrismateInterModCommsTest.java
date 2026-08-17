package com.aprism.prismate.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.aprism.api.imc.ImcMessage;

/**
 * Tests for the Prismate-owned inter-mod communication surface
 * (v26.2-Alpha.1 upstream alignment, mirroring Aprism core's
 * {@code InterModCommsImpl} from v26.3-Alpha.7): phase gating, queue drain
 * semantics, method-key filtering.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateInterModComms")
class PrismateInterModCommsTest {

    private final PrismateInterModComms comms = new PrismateInterModComms();

    @AfterEach
    void tearDown() {
        comms.clear();
    }

    @Nested
    @DisplayName("Phase gating")
    class PhaseGating {

        @Test
        @DisplayName("sendTo before INIT phase is rejected")
        void sendBeforeInitPhaseIsRejected() {
            assertThatThrownBy(() -> comms.sendTo("sender", "target", "method", "payload"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before INIT phase");
        }

        @Test
        @DisplayName("sendTo after INIT phase is accepted")
        void sendAfterInitPhaseIsAccepted() {
            comms.markInitPhaseReached();

            assertThat(comms.sendTo("sender", "target", "method", "payload")).isTrue();
            assertThat(comms.hasMessages("target")).isTrue();
        }

        @Test
        @DisplayName("clear resets the send window")
        void clearResetsTheSendWindow() {
            comms.markInitPhaseReached();
            comms.clear();

            assertThat(comms.isSendWindowOpen()).isFalse();
            assertThatThrownBy(() -> comms.sendTo("sender", "target", "method", "payload"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Message validation")
    class MessageValidation {

        @Test
        @DisplayName("blank addressing fields are rejected by ImcMessage")
        void blankAddressingFieldsAreRejected() {
            assertThatThrownBy(() -> new ImcMessage("", "method", "sender", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ImcMessage("target", "", "sender", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ImcMessage("target", "method", "", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Drain semantics")
    class DrainSemantics {

        @Test
        @DisplayName("getMessages drains the queue")
        void getMessagesDrainsTheQueue() {
            comms.markInitPhaseReached();
            comms.sendTo("a", "target", "m1", "p1");
            comms.sendTo("b", "target", "m2", "p2");

            List<ImcMessage> first = comms.getMessages("target");
            List<ImcMessage> second = comms.getMessages("target");

            assertThat(first).hasSize(2);
            assertThat(first.get(0).senderModId()).isEqualTo("a");
            assertThat(first.get(1).payload()).isEqualTo("p2");
            assertThat(second).isEmpty();
        }

        @Test
        @DisplayName("messages preserve send order")
        void messagesPreserveSendOrder() {
            comms.markInitPhaseReached();
            for (int i = 0; i < 5; i++) {
                comms.sendTo("sender", "target", "m" + i, i);
            }

            List<ImcMessage> drained = comms.getMessages("target");

            assertThat(drained).extracting(ImcMessage::methodKey)
                    .containsExactly("m0", "m1", "m2", "m3", "m4");
        }

        @Test
        @DisplayName("unknown recipient returns empty list")
        void unknownRecipientReturnsEmptyList() {
            assertThat(comms.getMessages("nobody")).isEmpty();
            assertThat(comms.hasMessages("nobody")).isFalse();
        }
    }

    @Nested
    @DisplayName("Method-key filtering")
    class MethodKeyFiltering {

        @Test
        @DisplayName("filter drains only matching method")
        void filterDrainsOnlyMatchingMethod() {
            comms.markInitPhaseReached();
            comms.sendTo("a", "target", "register", "r1");
            comms.sendTo("b", "target", "configure", "c1");
            comms.sendTo("c", "target", "register", "r2");

            List<ImcMessage> matched = comms.getMessages("target", "register");
            List<ImcMessage> remaining = comms.getMessages("target");

            assertThat(matched).extracting(ImcMessage::methodKey)
                    .containsExactly("register", "register");
            assertThat(matched).extracting(ImcMessage::payload)
                    .containsExactly("r1", "r2");
            assertThat(remaining).extracting(ImcMessage::methodKey)
                    .containsExactly("configure");
        }

        @Test
        @DisplayName("null filter returns empty without draining")
        void nullFilterReturnsEmptyWithoutDraining() {
            comms.markInitPhaseReached();
            comms.sendTo("a", "target", "method", "payload");

            assertThat(comms.getMessages("target", null)).isEmpty();
            assertThat(comms.hasMessages("target")).isTrue();
        }
    }

    @Nested
    @DisplayName("EmbeddedRuntime wiring")
    class RuntimeWiring {

        @Test
        @DisplayName("EmbeddedRuntime exposes the IMC surface")
        void runtimeExposesImc() {
            com.aprism.prismate.testsupport.FakeHostBridge bridge =
                    new com.aprism.prismate.testsupport.FakeHostBridge(
                            java.nio.file.Path.of("target/fake"), true,
                            com.aprism.prismate.host.EnvSide.CLIENT);
            EmbeddedRuntime runtime = EmbeddedRuntime.create(bridge,
                    com.aprism.prismate.config.PrismateConfig.load(bridge.gameDir()));

            assertThat(runtime.getInterModComms()).isNotNull();
            assertThat(runtime.getInterModComms()).isSameAs(runtime.getInterModComms());
        }
    }
}
