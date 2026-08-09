package com.example.soak;

import com.aprism.api.AprismContext;
import com.aprism.api.IAprismMod;

/**
 * Soak probe: depends on the virtual id {@code soak-api} (>=1.0.0), provided
 * by the soakapi pack. The harness asserts this pack initializes AFTER the
 * provider pack, proving dependency-resolved ordering across packs inside a
 * real game (v26.0-Alpha.5).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class ConsumerProbe implements IAprismMod {

    @Override
    public void onInitialize(AprismContext context) {
        System.out.println("[SOAK] consumer init");
    }

    @Override
    public void onComplete(AprismContext context) {
        System.out.println("[SOAK] consumer complete");
    }
}
