package com.example.prismatemix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a marker into the real {@code net.minecraft.client.Minecraft}
 * constructor so the Fabric smoke harness can assert that Prismate's mixin
 * passthrough reaches the host Mixin environment and weaves a genuine game
 * class. The target is declared as a string so this class compiles without
 * the Minecraft jar.
 *
 * @author BlockConnect@StarsailsClover
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public class MinecraftSmokeMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void prismateMixinProof(CallbackInfo ci) {
        System.out.println("[APRISM-MIXIN-PROOF] woven into net.minecraft.client.Minecraft by Aprism");
    }
}
