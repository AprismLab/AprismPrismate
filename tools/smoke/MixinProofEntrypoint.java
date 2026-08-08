package com.example.prismatemix;

import com.aprism.api.AprismContext;
import com.aprism.api.IAprismMod;

/**
 * Entrypoint for the Prismate mixin-proof smoke pack. Kept in a package
 * SEPARATE from the mixin classes: the host Mixin environment owns the mixin
 * package (per the mixin config) and forbids ordinary classloaders from
 * referencing classes inside it. This is the structure every real Fabric mod
 * follows.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class MixinProofEntrypoint implements IAprismMod {

    @Override
    public void onInitialize(AprismContext context) {
        System.out.println("[APRISM-MIXIN-PROOF] mod 'prismatemix' initialized "
                + "(mixin target: Minecraft)");
    }
}
