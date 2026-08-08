package com.aprism.prismate.runtime;

import java.nio.file.Path;
import java.util.Optional;

import com.aprism.api.ModContainer;
import com.aprism.manifest.AprismManifest;

/**
 * Mutable {@link ModContainer} for a Prismate-loaded {@code .aje} mod. The
 * instance field is set when the mod's entrypoint is constructed during
 * lifecycle dispatch.
 *
 * <p>Reference identity invariant (Aprism FACT.md 9.2): for a given mod id,
 * every lookup through the embedded runtime returns the SAME container
 * instance across the lifecycle.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateModContainer implements ModContainer {

    private final AprismManifest manifest;
    private final Path sourcePath;
    private Object instance;

    /**
     * @param manifest   the parsed mod manifest
     * @param sourcePath the path to the {@code .aje} pack on disk
     */
    public PrismateModContainer(AprismManifest manifest, Path sourcePath) {
        this.manifest = manifest;
        this.sourcePath = sourcePath;
    }

    /**
     * @return the parsed manifest
     */
    public AprismManifest getManifest() {
        return manifest;
    }

    /**
     * Sets the instantiated mod entrypoint object.
     *
     * @param instance the mod instance (may be {@code null})
     */
    public void setInstance(Object instance) {
        this.instance = instance;
    }

    @Override
    public String getId() {
        return manifest.id();
    }

    @Override
    public String getVersion() {
        return manifest.version();
    }

    @Override
    public String getDisplayName() {
        return manifest.displayName();
    }

    @Override
    public String getDescription() {
        return manifest.description();
    }

    @Override
    public Path getSourcePath() {
        return sourcePath;
    }

    @Override
    public Object getInstance() {
        return instance;
    }

    @Override
    public <T> Optional<T> getInstance(Class<T> type) {
        if (instance == null) {
            return Optional.empty();
        }
        return type.isInstance(instance) ? Optional.of(type.cast(instance)) : Optional.empty();
    }
}
