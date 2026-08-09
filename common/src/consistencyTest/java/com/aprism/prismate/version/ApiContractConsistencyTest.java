package com.aprism.prismate.version;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aprism.api.AprismContext;
import com.aprism.api.AprismPhase;
import com.aprism.api.IAprismMod;
import com.aprism.api.ModContainer;
import com.aprism.manifest.AprismManifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract-drift alarm for the Aprism mod API Prismate binds at runtime
 * (v26.1-Alpha.6). The composite build gives compile-time checks for direct
 * calls, but this test pins the RUNTIME contract Prismate actually depends on
 * — the lifecycle phase ordering it dispatches, the {@code IAprismMod} hooks
 * it invokes, the {@code AprismManifest} components it reads, and the
 * {@code ModContainer} getters it exposes. Any upstream rename, reorder, or
 * signature change fails loudly here instead of silently breaking Prismate
 * when the embedded Aprism core moves under us.
 *
 * <p>Complements {@link VersionLineConsistencyTest}, which guards the JE
 * version line; together they are Prismate's upstream-sync discipline.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Aprism mod-API contract consistency")
class ApiContractConsistencyTest {

    @Test
    @DisplayName("AprismPhase constants stay in the lifecycle dispatch order")
    void phaseOrderMatchesLifecycleDispatch() {
        // Prismate dispatches PREINIT -> INIT -> SETUP -> COMPLETE then the
        // side CLIENT/SERVER (docs 01 Section 4). The ordering is load-bearing
        // for the strict-phase invariant, so pin it exactly.
        assertThat(AprismPhase.values())
                .containsExactly(
                        AprismPhase.PREINIT,
                        AprismPhase.INIT,
                        AprismPhase.SETUP,
                        AprismPhase.COMPLETE,
                        AprismPhase.CLIENT,
                        AprismPhase.SERVER);
    }

    @Test
    @DisplayName("IAprismMod exposes the four lifecycle hooks with AprismContext")
    void iaprismModHookSetIsStable() throws Exception {
        Class<?> iface = IAprismMod.class;
        assertThat(iface.isInterface()).isTrue();

        // The four hooks Prismate invokes during lifecycle dispatch.
        for (String hook : List.of(
                "onInitialize", "onPreInitialize", "onSetup", "onComplete")) {
            Method m = iface.getMethod(hook, AprismContext.class);
            assertThat(m.getParameterCount()).as(hook).isEqualTo(1);
            assertThat(m.getReturnType()).as(hook + " returns void").isEqualTo(void.class);
        }
    }

    @Test
    @DisplayName("AprismManifest record keeps the components Prismate reads")
    void aprismManifestComponentsAreStable() {
        assertThat(AprismManifest.class.isRecord())
                .as("AprismManifest is a record").isTrue();

        List<String> components = Arrays.stream(AprismManifest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        // The components Prismate reads during discovery/extraction/injection
        // and rewrites in rewriteProvidesDeps. Pin the names; additions are
        // allowed (monotonic), removals/renames are breaks.
        assertThat(components).contains(
                "schemaVersion", "id", "version", "displayName", "description",
                "environment", "entrypoints", "mixins", "depends", "platforms",
                "accessWidener", "provides", "custom");
    }

    @Test
    @DisplayName("ModContainer keeps the getters Prismate surfaces")
    void modContainerGetterSetIsStable() throws Exception {
        Class<?> iface = ModContainer.class;
        assertThat(iface.isInterface()).isTrue();
        for (String getter : List.of(
                "getId", "getVersion", "getDisplayName", "getDescription", "getInstance")) {
            assertThat(iface.getMethod(getter))
                    .as(getter + " present").isNotNull();
        }
    }
}
