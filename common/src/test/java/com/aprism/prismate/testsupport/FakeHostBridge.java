package com.aprism.prismate.testsupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.host.HostBridge;

/**
 * A deterministic in-memory {@link HostBridge} for headless tests. Records
 * every injection call and lets each test choose whether host injection
 * succeeds (primary path) or fails (degraded fallback path).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class FakeHostBridge implements HostBridge {

    private Path gameDir;
    private final boolean injectionWorks;
    private final EnvSide side;
    private String mcVersion = "26.2";
    private final List<Path> injectedJars = new ArrayList<>();
    private final List<Path> injectedResourceDirs = new ArrayList<>();
    private final List<String> offeredMixinConfigs = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private com.aprism.prismate.host.HostTickListener tickListener;

    /**
     * @param gameDir        the fake game directory
     * @param injectionWorks whether {@link #injectJar} reports success
     * @param side           the fake distribution side
     */
    public FakeHostBridge(Path gameDir, boolean injectionWorks, EnvSide side) {
        this.gameDir = gameDir;
        this.injectionWorks = injectionWorks;
        this.side = side;
    }

    @Override
    public String loaderKey() {
        return "Fa";
    }

    @Override
    public String loaderName() {
        return "FakeFabric";
    }

    @Override
    public String hostLoaderVersion() {
        return "0.16.14";
    }

    @Override
    public String minecraftVersion() {
        return mcVersion;
    }

    /**
     * Overrides the reported Minecraft version (v26.1-Alpha.1 version-line
     * gate tests need out-of-line versions).
     *
     * @param version the version string to report
     */
    public void setMinecraftVersion(String version) {
        this.mcVersion = version;
    }

    @Override
    public EnvSide side() {
        return side;
    }

    @Override
    public Path gameDir() {
        return gameDir;
    }

    /**
     * Re-points the fake game directory mid-test (used by dedup tests to
     * flip the publish target from failing to healthy).
     *
     * @param newGameDir the replacement game directory
     */
    public void setGameDir(Path newGameDir) {
        this.gameDir = newGameDir;
    }

    @Override
    public boolean injectJar(Path jar) {
        if (!injectionWorks) {
            return false;
        }
        injectedJars.add(jar);
        return true;
    }

    @Override
    public void injectResourceDir(Path resourcesDir) {
        injectedResourceDirs.add(resourcesDir);
    }

    @Override
    public void offerMixinConfig(String configName) {
        offeredMixinConfigs.add(configName);
    }

    @Override
    public boolean registerTickHook(com.aprism.prismate.host.HostTickListener listener) {
        this.tickListener = listener;
        return true;
    }

    /**
     * Simulates the host tick loop: fires the registered tick hook for the
     * given number of ticks (tests drive GameTickEvent deliveries with it).
     *
     * @param ticks how many ticks to fire
     */
    public void fireTicks(int ticks) {
        for (long i = 0; i < ticks; i++) {
            if (tickListener != null) {
                tickListener.onTick(i);
            }
        }
    }

    @Override
    public void log(String message) {
        logLines.add(message);
    }

    public List<Path> injectedJars() {
        return List.copyOf(injectedJars);
    }

    public List<Path> injectedResourceDirs() {
        return List.copyOf(injectedResourceDirs);
    }

    public List<String> offeredMixinConfigs() {
        return List.copyOf(offeredMixinConfigs);
    }

    public List<String> logLines() {
        return List.copyOf(logLines);
    }
}
