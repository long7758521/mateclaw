package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the agent-vs-workspace basePath precedence rules used by
 * {@link AgentGraphBuilder#resolveAgentBasePath(String, String)}.
 *
 * <p>The UI advertises agent-level paths as "relative to the workspace root",
 * so a relative agent override must compose with the workspace basePath rather
 * than fall through to the JVM working directory.
 */
class AgentGraphBuilderBasePathResolutionTest {

    @Test
    @DisplayName("Both null/blank → null (no working directory configured)")
    void noOverrideNoWorkspace_returnsNull() {
        assertNull(AgentGraphBuilder.resolveAgentBasePath(null, null));
        assertNull(AgentGraphBuilder.resolveAgentBasePath("", ""));
        assertNull(AgentGraphBuilder.resolveAgentBasePath("  ", null));
    }

    @Test
    @DisplayName("No agent override → workspace basePath inherited verbatim")
    void noOverride_inheritsWorkspace() {
        assertEquals("/srv/ws-root",
                AgentGraphBuilder.resolveAgentBasePath(null, "/srv/ws-root"));
        assertEquals("/srv/ws-root",
                AgentGraphBuilder.resolveAgentBasePath("", "/srv/ws-root"));
    }

    @Test
    @DisplayName("Agent override is absolute → used verbatim, workspace ignored")
    @DisabledOnOs(OS.WINDOWS)
    void absoluteOverride_usedAsIs_unix() {
        assertEquals("/opt/agents/code-review",
                AgentGraphBuilder.resolveAgentBasePath("/opt/agents/code-review", "/srv/ws-root"));
        assertEquals("/opt/agents/code-review",
                AgentGraphBuilder.resolveAgentBasePath("/opt/agents/code-review", null));
    }

    @Test
    @DisplayName("Agent override is absolute (Windows) → used verbatim")
    @EnabledOnOs(OS.WINDOWS)
    void absoluteOverride_usedAsIs_windows() {
        assertEquals("C:\\agents\\code-review",
                AgentGraphBuilder.resolveAgentBasePath("C:\\agents\\code-review", "C:\\ws-root"));
    }

    @Test
    @DisplayName("Relative agent override + workspace basePath → resolved under workspace")
    void relativeOverride_resolvedUnderWorkspace() {
        String expected = Paths.get("/srv/ws-root").resolve("projects/code-review").toString();
        assertEquals(expected,
                AgentGraphBuilder.resolveAgentBasePath("projects/code-review", "/srv/ws-root"));
    }

    @Test
    @DisplayName("Relative agent override with no workspace → used verbatim (legacy fallback)")
    void relativeOverride_noWorkspace_usedAsIs() {
        assertEquals("projects/code-review",
                AgentGraphBuilder.resolveAgentBasePath("projects/code-review", null));
        assertEquals("projects/code-review",
                AgentGraphBuilder.resolveAgentBasePath("projects/code-review", ""));
    }

    @Test
    @DisplayName("Blank workspace basePath treated like null when override is relative")
    void relativeOverride_blankWorkspace_usedAsIs() {
        assertEquals("agent-dir",
                AgentGraphBuilder.resolveAgentBasePath("agent-dir", "   "));
    }
}
