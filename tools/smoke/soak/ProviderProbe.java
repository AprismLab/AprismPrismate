package com.example.soak;

import com.aprism.api.AprismContext;
import com.aprism.api.IAprismMod;

/**
 * Soak probe: provides the virtual id {@code soak-api} (pack id
 * {@code soakapi}). The consumer pack depends on this virtual id, so the
 * harness can assert that Prismate resolves {@code provides} aliases and
 * orders the provider before the dependent inside a real game
 * (v26.0-Alpha.5).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class ProviderProbe implements IAprismMod {

    @Override
    public void onInitialize(AprismContext context) {
        System.out.println("[SOAK] provider init");
    }

    @Override
    public void onComplete(AprismContext context) {
        System.out.println("[SOAK] provider complete");
    }
}
