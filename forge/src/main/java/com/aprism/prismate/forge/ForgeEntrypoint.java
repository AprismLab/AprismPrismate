package com.aprism.prismate.forge;

import java.util.logging.Logger;

/**
 * Forge (classic) entrypoint STUB (FACT.md DECISION-1, re-confirmed in
 * v26.0-Alpha.5). The entire v26.0 line ships Fabric + NeoForge only; this
 * class reserves the Forge slot so the module compiles and the {@code -Fo-}
 * artifact naming is ready, but it refuses to do any work with a visible,
 * named error. Forge scope defers to post-1.0.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class ForgeEntrypoint {

    private static final Logger LOG = Logger.getLogger("prismate.forge");

    /**
     * Refuses to boot: Forge (classic) is out of the v26.0 line's scope.
     */
    public ForgeEntrypoint() {
        LOG.severe("AprismPrismate: Forge (classic) support is not implemented in this build. "
                + "Prismate supports Fabric and NeoForge for the v26.0 line (FACT.md "
                + "DECISION-1, re-confirmed at v26.0-Alpha.5). Remove Prismate from this "
                + "Forge instance; Forge scope is deferred to post-1.0.");
    }
}
