package com.example.ressmoke;

import java.io.InputStream;

import com.aprism.api.AprismContext;
import com.aprism.api.IAprismMod;

/**
 * Smoke probe for Prismate's resource-directory injection on Fabric. Loads a
 * resource that exists only in the pack's extracted {@code resources/}
 * directory (injected into the Knot classloader) and prints a marker so the
 * real-game harness can assert visibility.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class ResourceProbe implements IAprismMod {

    @Override
    public void onInitialize(AprismContext context) {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("assets/prismatesmoke/lang/en_us.json")) {
            boolean visible = in != null;
            System.out.println("[RESSMOKE] resource visible=" + visible);
        } catch (Exception e) {
            System.out.println("[RESSMOKE] resource probe failed: " + e);
        }
    }
}
