package com.aprism.prismate.forge;

import java.util.logging.Logger;

/**
 * Forge (classic) entrypoint STUB (FACT.md DECISION-1). The Alpha line ships
 * Fabric + NeoForge first; this class reserves the Forge slot so the module
 * compiles and the {@code -Fo-} artifact naming is ready, but it refuses to
 * do any work with a visible, named error. Forge support enters scope no
 * earlier than v26.0-Alpha.5.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class ForgeEntrypoint {

    private static final Logger LOG = Logger.getLogger("prismate.forge");

    /**
     * Refuses to boot: Forge (legacy) is out of Alpha scope.
     */
    public ForgeEntrypoint() {
        LOG.severe("AprismPrismate: Forge (classic) support is not implemented in this build. "
                + "Prismate currently supports Fabric and NeoForge only (see FACT.md "
                + "DECISION-1). Remove Prismate from this Forge instance, or wait for "
                + "v26.0-Alpha.5+ where Forge scope will be re-evaluated.");
    }
}
