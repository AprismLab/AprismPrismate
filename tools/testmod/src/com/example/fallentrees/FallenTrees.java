package com.example.fallentrees;

import com.aprism.api.AprismContext;
import com.aprism.api.AprismEventBus;
import com.aprism.api.AprismMod;
import com.aprism.api.IAprismMod;
import com.aprism.api.gameevent.GameTickEvent;

/**
 * Fallen-trees long-term test mod (v26.7 line vehicle).
 *
 * <p>Goal: real-content behavioral parity measurement between the Aprism
 * agent and the Prismate bridge. This skeleton exercises the surfaces a
 * production worldgen mod touches: lifecycle phases, event-bus subscription
 * (game tick), registry round-trip, and pack resources visibility.
 *
 * <p>Every observable prints a [FALLENTREES] marker so both loaders' logs can
 * be diffed mechanically.
 */
@AprismMod("fallentrees")
public class FallenTrees implements IAprismMod {

    private static final String MARKER = "[FALLENTREES]";

    /** Tick events observed so far (bounded logging). */
    private long tickCount = 0;

    @Override
    public void onPreInitialize(AprismContext context) {
        System.out.println(MARKER + " PREINIT loader-side, mod="
                + context.getMod().getId() + " v" + context.getMod().getVersion());
    }

    @Override
    public void onInitialize(AprismContext context) {
        System.out.println(MARKER + " INIT begin");

        // Event-bus subscription attempt: does this loader deliver game ticks?
        try {
            AprismEventBus bus = context.getEventBus();
            bus.register(GameTickEvent.class, event -> {
                tickCount++;
                if (tickCount <= 3) {
                    System.out.println(MARKER + " TICK " + tickCount
                            + " stage=" + event.getStage());
                } else if (tickCount == 4) {
                    System.out.println(MARKER + " TICK stream confirmed"
                            + " (further ticks not logged)");
                }
            });
            System.out.println(MARKER + " INIT GameTickEvent listener registered OK");
        } catch (Throwable t) {
            System.out.println(MARKER + " INIT GameTickEvent registration FAILED: " + t);
        }

        // Registry round-trip: register a marker entry and read it back.
        try {
            context.getRegistry().register("fallentrees", "worldgen_probe",
                    "fallen-tree-feature-v0");
            Object raw = context.getRegistry().get("fallentrees", "worldgen_probe");
            Object value = raw instanceof java.util.Optional<?> opt
                    ? opt.orElse(null)
                    : raw;
            System.out.println(MARKER + " INIT registry round-trip "
                    + ("fallen-tree-feature-v0".equals(value) ? "OK" : "MISMATCH: " + value)
                    + " (raw type: " + (raw == null ? "null" : raw.getClass().getSimpleName()) + ")");
        } catch (Throwable t) {
            System.out.println(MARKER + " INIT registry round-trip FAILED: " + t);
        }

        System.out.println(MARKER + " INIT end");
    }

    @Override
    public void onSetup(AprismContext context) {
        // Resource visibility probes: the pack's data/ directory must be
        // reachable through the entrypoint classloader for datapack-driven
        // worldgen to work (v0.2: full worldgen JSON chain).
        ClassLoader cl = FallenTrees.class.getClassLoader();
        String probe = "data/fallentrees/worldgen_probe.txt";
        String cfg = "data/fallentrees/worldgen/configured_feature/fallen_oak.json";
        String placed = "data/fallentrees/worldgen/placed_feature/fallen_oak_markers.json";
        String biome = "data/minecraft/worldgen/biome/forest.json";
        System.out.println(MARKER + " SETUP resource probes:"
                + " probe=" + (cl.getResource(probe) != null)
                + " fallenOakCfg=" + (cl.getResource(cfg) != null)
                + " placed=" + (cl.getResource(placed) != null)
                + " forestOverride=" + (cl.getResource(biome) != null));

        // v26.17-A1 content-binding reachability probe: can the entrypoint
        // classloader see MC's registry system? This determines whether a
        // Prismate-loaded .aje can programmatically register items/blocks.
        String[] mcClasses = {
                "net.minecraft.core.registries.BuiltInRegistries",
                "net.minecraft.core.Registry",
                "net.minecraft.resources.Identifier",
                "net.minecraft.world.item.Item",
                "net.minecraft.world.level.block.Block"
        };
        for (String mcClass : mcClasses) {
            try {
                Class.forName(mcClass, false, cl);
                System.out.println(MARKER + " SETUP MC reachability: " + mcClass + " OK");
            } catch (Throwable t) {
                System.out.println(MARKER + " SETUP MC reachability: " + mcClass
                        + " UNREACHABLE (" + t.getClass().getSimpleName() + ")");
            }
        }

        // v26.18-A1 reflective content-binding proof: can we not only SEE
        // the registry API but actually INVOKE it via reflection? This
        // confirms the bridge from 'classloader reachable' to 'usable'.
        try {
            Class<?> builtInRegistries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, cl);
            Class<?> registryClass = Class.forName("net.minecraft.core.Registry", true, cl);
            java.lang.reflect.Field itemRegField = builtInRegistries.getField("ITEM");
            Object itemRegistry = itemRegField.get(null);
            System.out.println(MARKER + " SETUP REGISTRY PROOF: BuiltInRegistries.ITEM resolved ("
                    + itemRegistry.getClass().getSimpleName() + ")");

            // Verify register() method is accessible
            java.lang.reflect.Method[] allMethods = registryClass.getMethods();
            java.util.List<java.lang.reflect.Method> registerMethods = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : allMethods) {
                if (m.getName().equals("register")) {
                    registerMethods.add(m);
                }
            }
            System.out.println(MARKER + " SETUP REGISTRY PROOF: Registry.register() candidates: "
                    + registerMethods.size());
            for (java.lang.reflect.Method m : registerMethods) {
                System.out.println(MARKER + "   -> " + m);
            }

            // v26.19-A1: discover Identifier static factories (0 public ctors
            // means the id object must come from a factory method).
            Class<?> resLocClass = Class.forName("net.minecraft.resources.Identifier", true, cl);
            Object testId = null;
            System.out.println(MARKER + " IDRES Identifier factory discovery:");
            for (java.lang.reflect.Method m : resLocClass.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || m.getDeclaringClass() != resLocClass) {
                    continue;
                }
                System.out.println(MARKER + "   ID static -> " + m);
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (testId == null && p.length == 2 && p[0] == String.class && p[1] == String.class
                            && m.getReturnType() == resLocClass) {
                        testId = m.invoke(null, "fallentrees", "test_item");
                        System.out.println(MARKER + "   ID created via " + m.getName()
                                + "(ns,path): " + testId);
                    }
                } catch (Throwable ignored) {
                    // factory not usable with these args; keep scanning
                }
            }
            if (testId == null) {
                for (java.lang.reflect.Method m : resLocClass.getMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            || m.getDeclaringClass() != resLocClass) {
                        continue;
                    }
                    Class<?>[] p = m.getParameterTypes();
                    try {
                        if (p.length == 1 && p[0] == String.class && m.getReturnType() == resLocClass) {
                            testId = m.invoke(null, "fallentrees:test_item");
                            System.out.println(MARKER + "   ID created via " + m.getName()
                                    + "(joined): " + testId);
                            break;
                        }
                    } catch (Throwable ignored) {
                        // keep scanning
                    }
                }
            }
            System.out.println(MARKER + " IDRES Identifier instance: " + (testId != null ? "OK" : "NONE"));

            // v26.19-A2 targeted path: ResourceKey.create + Properties.setId.
            // A1 discovery proved setId(ResourceKey) exists and Identifier is
            // obtainable via fromNamespaceAndPath; the adaptive fallback below
            // only runs when this fails (on success it would leave unregistered
            // intrusive holders that crash registry freeze).
            boolean targetedOk = false;
            if (testId != null) {
                try {
                    Class<?> rkClass = Class.forName("net.minecraft.resources.ResourceKey", true, cl);
                    Object itemRegKey;
                    try {
                        Class<?> registriesClass = Class.forName("net.minecraft.core.registries.Registries", true, cl);
                        itemRegKey = registriesClass.getField("ITEM").get(null);
                        System.out.println(MARKER + " IDRES2 Registries.ITEM resolved: " + itemRegKey);
                    } catch (Throwable t) {
                        java.lang.reflect.Method crk = rkClass.getMethod("createRegistryKey", resLocClass);
                        Object itemsRegId = resLocClass.getMethod("withDefaultNamespace", String.class)
                                .invoke(null, "item");
                        itemRegKey = crk.invoke(null, itemsRegId);
                        System.out.println(MARKER + " IDRES2 Registries.ITEM via createRegistryKey: " + itemRegKey);
                    }
                    Object itemKey = rkClass.getMethod("create", rkClass, resLocClass)
                            .invoke(null, itemRegKey, testId);
                    System.out.println(MARKER + " IDRES2 item ResourceKey: " + itemKey);

                    Class<?> itemClass = Class.forName("net.minecraft.world.item.Item", true, cl);
                    Class<?> itemPropsClass = Class.forName("net.minecraft.world.item.Item$Properties", true, cl);
                    Object props = itemPropsClass.getConstructor().newInstance();
                    itemPropsClass.getMethod("setId", rkClass).invoke(props, itemKey);
                    Object testItem = itemClass.getConstructor(itemPropsClass).newInstance(props);
                    System.out.println(MARKER + " IDRES2 Item constructed with id");

                    java.lang.reflect.Method idRegister = registryClass.getMethod("register",
                            registryClass, resLocClass, Object.class);
                    Object result = idRegister.invoke(null, itemRegistry, testId, testItem);
                    System.out.println(MARKER + " IDRES2 REGISTER SUCCESS: "
                            + (result != null ? result.getClass().getSimpleName() : "null"));
                    targetedOk = true;

                    Object readback = itemRegistry.getClass().getMethod("get", resLocClass)
                            .invoke(itemRegistry, testId);
                    System.out.println(MARKER + " IDRES2 readback identity: " + (readback == testItem)
                            + " (" + readback + ")");
                } catch (Throwable t) {
                    System.out.println(MARKER + " IDRES2 FAILED: " + rootMessage(t));
                    Throwable c = t;
                    while ((c = c.getCause()) != null) {
                        System.out.println(MARKER + "   caused by: " + c.getClass().getSimpleName()
                                + ": " + c.getMessage());
                        if (c.getStackTrace().length > 0) {
                            System.out.println(MARKER + "   at " + c.getStackTrace()[0]);
                        }
                    }
                }
            }

            // v26.19-A1 fallback: discover Item$Properties id-setting surface
            // and Item's own id field, then adaptively try to construct an
            // Item that carries its id (fixing v26.18's 'Item id not set').
            if (!targetedOk) try {
                Class<?> itemClass = Class.forName("net.minecraft.world.item.Item", true, cl);
                Class<?> itemPropsClass = Class.forName("net.minecraft.world.item.Item$Properties", true, cl);

                System.out.println(MARKER + " IDRES Item$Properties methods:");
                java.util.List<java.lang.reflect.Method> propIdCandidates = new java.util.ArrayList<>();
                for (java.lang.reflect.Method m : itemPropsClass.getMethods()) {
                    if (m.getDeclaringClass() != itemPropsClass) {
                        continue;
                    }
                    String n = m.getName().toLowerCase();
                    boolean idish = n.contains("id") || n.contains("key") || n.contains("location")
                            || n.contains("identifier") || n.contains("registryname");
                    System.out.println(MARKER + "   PROPS -> " + m + (idish ? "  [idish]" : ""));
                    if (idish && m.getParameterCount() == 1) {
                        propIdCandidates.add(m);
                    }
                }

                System.out.println(MARKER + " IDRES Item declared fields:");
                for (java.lang.reflect.Field f : itemClass.getDeclaredFields()) {
                    System.out.println(MARKER + "   FIELD -> " + f.getName() + " : "
                            + f.getType().getName());
                }

                // Adaptive attempt 1: Properties setter with Identifier instance
                boolean propsSet = false;
                for (java.lang.reflect.Method m : propIdCandidates) {
                    Class<?> pt = m.getParameterTypes()[0];
                    try {
                        if (testId != null && pt.isInstance(testId)) {
                            Object props = itemPropsClass.getConstructor().newInstance();
                            m.invoke(props, testId);
                            System.out.println(MARKER + " IDRES Properties." + m.getName()
                                    + "(Identifier) OK");
                            Object testItem = itemClass.getConstructor(itemPropsClass).newInstance(props);
                            attemptRegistration(itemRegistry, registryClass, testItem);
                            propsSet = true;
                            break;
                        }
                    } catch (Throwable t) {
                        System.out.println(MARKER + "   Properties." + m.getName() + " attempt failed: "
                                + rootMessage(t));
                    }
                }

                // Adaptive attempt 2: direct Item field write
                if (!propsSet) {
                    System.out.println(MARKER + " IDRES falling back to direct Item field write");
                    for (java.lang.reflect.Field f : itemClass.getDeclaredFields()) {
                        String fn = f.getName().toLowerCase();
                        if (!(fn.contains("id") || fn.contains("key") || fn.contains("identifier"))) {
                            continue;
                        }
                        try {
                            Object props = itemPropsClass.getConstructor().newInstance();
                            Object testItem = itemClass.getConstructor(itemPropsClass).newInstance(props);
                            f.setAccessible(true);
                            if (testId != null && f.getType().isInstance(testId)) {
                                f.set(testItem, testId);
                                System.out.println(MARKER + " IDRES field " + f.getName() + " <- Identifier OK");
                                attemptRegistration(itemRegistry, registryClass, testItem);
                                propsSet = true;
                                break;
                            }
                        } catch (Throwable t) {
                            System.out.println(MARKER + "   field " + f.getName() + " attempt failed: "
                                    + rootMessage(t));
                        }
                    }
                }
                if (!propsSet) {
                    System.out.println(MARKER + " IDRES: no id-set path succeeded this run");
                }
            } catch (Throwable t) {
                System.out.println(MARKER + " IDRES discovery FAILED: " + rootMessage(t));
            }
        } catch (Throwable t) {
            System.out.println(MARKER + " SETUP REGISTRY PROOF FAILED: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void onComplete(AprismContext context) {
        System.out.println(MARKER + " COMPLETE fallen-trees ready"
                + " (ticks observed so far: " + tickCount + ")");
        // v26.12-A1: the deterministic placement check is driven externally
        // (RCON 'execute positioned X Y Z run place feature ...'), after which
        // the spawned logs are verified by inspecting the world via the same
        // server console ('execute if block' probes) - see FACT 5m. The mod's
        // own tick callback stays observer-only to keep this loader-neutral.
        System.out.println(MARKER + " COMPLETE placement verification window open;"
                + " awaiting RCON 'place feature fallentrees:fallen_oak_markers'");
    }

    /**
     * Attempts Registry.register() with the given Item instance using the
     * String-keyed overload and reports the outcome with full cause chain.
     */
    private void attemptRegistration(Object itemRegistry, Class<?> registryClass, Object testItem) {
        try {
            java.lang.reflect.Method stringRegister = registryClass.getMethod("register",
                    registryClass, String.class, Object.class);
            Object result = stringRegister.invoke(null, itemRegistry, "fallentrees:test_item", testItem);
            System.out.println(MARKER + " IDRES REGISTER INVOKED OK"
                    + " (result: " + (result != null ? result.getClass().getSimpleName() : "null") + ")");
        } catch (Throwable t) {
            System.out.println(MARKER + " IDRES register attempt FAILED: " + rootMessage(t));
        }
    }

    /** Unwraps reflective invocation chains down to the root cause message. */
    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
