package com.aprism.prismate;

/**
 * Detects whether the Aprism javaagent is active in this JVM. Prismate and
 * the Aprism agent are mutually exclusive in one instance (docs 01 Section
 * 9.2): with both present there would be two definitions of
 * {@code com.aprism.api} and two lifecycles, causing ClassCastException and
 * double initialization.
 *
 * <p>Detection mechanism (OPEN-3 interim resolution): the Aprism agent
 * currently sets no system property (verified against v26.0-Alpha.8), so
 * Prismate probes the system classloader for {@code com.aprism.loader.AprismRuntime}.
 * Prismate never embeds {@code com.aprism.loader} (library mode embeds only
 * the API and manifest modules), so that class can only be present when the
 * Aprism agent jar is attached. The {@code aprism.agent.active} system
 * property is honored as a forward-compatible hook for when the Aprism agent
 * starts setting it.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class AgentConflictDetector {

    /** Forward-compatible property the Aprism agent may set in the future. */
    public static final String AGENT_PROPERTY = "aprism.agent.active";

    /** The agent's runtime singleton class, only present when the agent is attached. */
    private static final String AGENT_RUNTIME_CLASS = "com.aprism.loader.AprismRuntime";

    private AgentConflictDetector() {
    }

    /**
     * @return whether the Aprism javaagent appears to be active in this JVM
     */
    public static boolean isAprismAgentPresent() {
        if (Boolean.getBoolean(AGENT_PROPERTY)) {
            return true;
        }
        try {
            Class.forName(AGENT_RUNTIME_CLASS, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * @return the human-readable refusal message shown when the agent is present
     */
    public static String refusalMessage() {
        return "AprismPrismate refuses to start: the Aprism javaagent is active in this JVM. "
                + "Prismate and the Aprism agent are mutually exclusive in one instance "
                + "(two copies of the Aprism API and two lifecycles would collide). "
                + "Either remove the Aprism -javaagent JVM argument, or remove AprismPrismate "
                + "from the mods folder. Choose one, then start the game again.";
    }
}
