package com.example.soak;

import com.aprism.api.AprismContext;
import com.aprism.api.IAprismMod;

/**
 * Soak probe: a plain Aprism-native mod with no dependencies. Prints markers
 * at INIT and COMPLETE so the soak harness can assert the multi-pack
 * lifecycle inside a real game (v26.0-Alpha.5).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class CoreProbe implements IAprismMod {

    @Override
    public void onInitialize(AprismContext context) {
        System.out.println("[SOAK] core init");
    }

    @Override
    public void onComplete(AprismContext context) {
        System.out.println("[SOAK] core complete");
    }
}
