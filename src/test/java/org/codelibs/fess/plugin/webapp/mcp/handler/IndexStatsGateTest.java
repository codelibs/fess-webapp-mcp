/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.plugin.webapp.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.auth.McpPrincipal;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.GetDocumentTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.IndexStatsTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.SearchTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.SuggestTool;
import org.junit.jupiter.api.Test;

/**
 * Cross-handler coverage for the {@code get_index_stats} permission gate.
 * <p>
 * {@code tools/list}, {@code tools/call}, {@code resources/list}, and {@code resources/read}
 * must all agree about whether an unauthorised caller may see or use this primitive -- hiding
 * it from one list and not the other, or refusing the call/read with a distinguishable error,
 * was the exact defect an earlier review called out.
 * </p>
 * <p>
 * Every tool double here overrides {@link IndexStatsTool#getIndexStatsPermissions()} (or uses a
 * tool whose {@code getRequiredPermissions()} never touches {@code ComponentUtil} in the first
 * place), so this entire suite runs without a live DI container -- exactly like
 * {@code McpToolTest#testIndexStatsIsPermissionGated}.
 * </p>
 */
public class IndexStatsGateTest {

    /** {@code get_index_stats} gated by the real default permission ({@code Radmin-api}), without touching the DI container. */
    private static final class GatedIndexStatsTool extends IndexStatsTool {
        @Override
        protected String getIndexStatsPermissions() {
            return "Radmin-api";
        }
    }

    /** {@code get_index_stats} with the gate disabled, mirroring a blank {@code mcp.tools.index_stats.permissions}. */
    private static final class UngatedIndexStatsTool extends IndexStatsTool {
        @Override
        protected String getIndexStatsPermissions() {
            return "";
        }
    }

    private static List<McpTool> fourTools(final McpTool indexStatsTool) {
        return List.of(new SearchTool(), indexStatsTool, new SuggestTool(), new GetDocumentTool());
    }

    /** A {@code tools/list} handler with a fixed TTL and a settable auth mode, so neither touches the DI container. */
    private static final class FixedToolsListHandler extends ToolsListHandler {

        private final String authMode;

        FixedToolsListHandler(final List<McpTool> tools, final String authMode) {
            super(tools);
            this.authMode = authMode;
        }

        @Override
        protected long getTtlMs() {
            return 3_600_000L;
        }

        @Override
        protected String getAuthMode() {
            return authMode;
        }
    }

    /** A {@code resources/list} handler with a fixed TTL and a settable auth mode, so neither touches the DI container. */
    private static final class FixedResourcesListHandler extends ResourcesListHandler {

        private final String authMode;

        FixedResourcesListHandler(final McpTool indexStatsTool, final String authMode) {
            super(indexStatsTool);
            this.authMode = authMode;
        }

        @Override
        protected long getTtlMs() {
            return 3_600_000L;
        }

        @Override
        protected String getAuthMode() {
            return authMode;
        }
    }

    /** A {@code resources/read} handler with a fixed TTL, so it never touches the DI container for its TTL. */
    private static final class FixedResourcesReadHandler extends ResourcesReadHandler {

        FixedResourcesReadHandler(final McpTool indexStatsTool) {
            super(indexStatsTool);
        }

        @Override
        protected long getTtlMs() {
            return 0L;
        }
    }

    private final ToolsListHandler toolsListHandler = new FixedToolsListHandler(fourTools(new GatedIndexStatsTool()), "none");
    private final ToolsCallHandler toolsCallHandler = new ToolsCallHandler(fourTools(new GatedIndexStatsTool()));
    private final ResourcesListHandler resourcesListHandler = new FixedResourcesListHandler(new GatedIndexStatsTool(), "none");
    private final ResourcesReadHandler resourcesReadHandler = new FixedResourcesReadHandler(new GatedIndexStatsTool());

    private McpCallContext contextWithPermissions(final Set<String> permissions) {
        return new McpCallContext(null, null, Map.of(), new McpPrincipal(null, Set.of(), permissions));
    }

    private McpCallContext callContext(final String toolName, final Set<String> permissions) {
        return new McpCallContext(null, null, Map.of("name", toolName, "arguments", Map.of()),
                new McpPrincipal(null, Set.of(), permissions));
    }

    private McpCallContext readContext(final String uri, final Set<String> permissions) {
        return new McpCallContext(null, null, Map.of("uri", uri), new McpPrincipal(null, Set.of(), permissions));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testIndexStatsIsHiddenFromBothListsWithoutPermission() {
        final McpCallContext anonymous = contextWithPermissions(Set.of());

        final List<Map<String, Object>> tools = (List<Map<String, Object>>) toolsListHandler.handle(anonymous).get("tools");
        assertTrue(tools.stream().noneMatch(t -> "get_index_stats".equals(t.get("name"))), "leaks the index name and JVM heap");

        final List<Map<String, Object>> resources = (List<Map<String, Object>>) resourcesListHandler.handle(anonymous).get("resources");
        assertTrue(resources.stream().noneMatch(r -> "fess://index/stats".equals(r.get("uri"))),
                "tools/list and resources/list must agree about the same primitive");
    }

    @Test
    public void testIndexStatsCallIsRefusedWithoutPermission() {
        final McpError error = assertThrows(McpError.class, () -> toolsCallHandler.handle(callContext("get_index_stats", Set.of())));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode(), "a hidden tool is indistinguishable from an unknown one");
        assertEquals(200, error.getHttpStatus());
    }

    @Test
    public void testIndexStatsReadIsRefusedWithoutPermission() {
        final McpError error = assertThrows(McpError.class, () -> resourcesReadHandler.handle(readContext("fess://index/stats", Set.of())));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(200, error.getHttpStatus());
    }

    @Test
    public void testIndexStatsCallRefusalIsByteIdenticalToAnUnknownTool() {
        final McpError hidden = assertThrows(McpError.class, () -> toolsCallHandler.handle(callContext("get_index_stats", Set.of())));
        final McpError unknown = assertThrows(McpError.class, () -> toolsCallHandler.handle(callContext("no_such_tool", Set.of())));

        assertEquals(unknown.getErrorCode(), hidden.getErrorCode());
        assertEquals(unknown.getHttpStatus(), hidden.getHttpStatus());
        assertEquals("Unknown tool: get_index_stats", hidden.getMessage(), "must not disclose that the tool exists");
    }

    @Test
    public void testIndexStatsReadRefusalIsByteIdenticalToAnUnknownResource() {
        final McpError hidden =
                assertThrows(McpError.class, () -> resourcesReadHandler.handle(readContext("fess://index/stats", Set.of())));
        final McpError unknown =
                assertThrows(McpError.class, () -> resourcesReadHandler.handle(readContext("fess://unknown/thing", Set.of())));

        assertEquals(unknown.getErrorCode(), hidden.getErrorCode());
        assertEquals(unknown.getHttpStatus(), hidden.getHttpStatus());
        assertEquals("Resource not found: fess://index/stats", hidden.getMessage(), "must not disclose that the resource exists");
    }

    @Test
    public void testIndexStatsIsVisibleWithPermission() {
        final McpCallContext admin = contextWithPermissions(Set.of("Radmin-api"));

        final List<?> tools = (List<?>) toolsListHandler.handle(admin).get("tools");
        assertEquals(4, tools.size());

        final List<?> resources = (List<?>) resourcesListHandler.handle(admin).get("resources");
        assertEquals(1, resources.size());
    }

    @Test
    public void testBlankPermissionsSettingRestoresTheOldBehaviourForAnonymousCallers() {
        // Resolution #4 (blank direction): an operator who sets mcp.tools.index_stats.permissions
        // to blank must see the tool/resource even for an anonymous caller.
        final ToolsListHandler ungatedToolsList = new FixedToolsListHandler(fourTools(new UngatedIndexStatsTool()), "none");
        final List<?> tools = (List<?>) ungatedToolsList.handle(contextWithPermissions(Set.of())).get("tools");
        assertEquals(4, tools.size(), "a blank permissions setting must disable the gate, even for an anonymous caller");

        final ResourcesListHandler ungatedResourcesList = new FixedResourcesListHandler(new UngatedIndexStatsTool(), "none");
        final List<?> resources = (List<?>) ungatedResourcesList.handle(contextWithPermissions(Set.of())).get("resources");
        assertEquals(1, resources.size());
    }

    @Test
    public void testDefaultPermissionsSettingClosesTheGateForAnonymousCallersInNoneMode() {
        // Resolution #4 (default direction): in none mode there is no principal with
        // permissions, so the non-blank default (Radmin-api) closes the gate by default. This
        // is intended, not a bug -- see the brief's resolution #4.
        final List<?> tools = (List<?>) toolsListHandler.handle(contextWithPermissions(Set.of())).get("tools");
        assertEquals(3, tools.size(), "none mode plus the default Radmin-api requirement must close the gate by default");
    }

    @Test
    public void testToolsListCacheScopeIsPrivateWhenAuthIsOn() {
        final ToolsListHandler none = new FixedToolsListHandler(fourTools(new GatedIndexStatsTool()), "none");
        final ToolsListHandler fessToken = new FixedToolsListHandler(fourTools(new GatedIndexStatsTool()), "fess_token");

        assertEquals("public", none.handle(contextWithPermissions(Set.of())).get("cacheScope"));
        assertEquals("private", fessToken.handle(contextWithPermissions(Set.of())).get("cacheScope"),
                "a public scope may be shared across callers even on an authenticated endpoint");
    }

    @Test
    public void testResourcesListCacheScopeIsPrivateWhenAuthIsOn() {
        final ResourcesListHandler none = new FixedResourcesListHandler(new GatedIndexStatsTool(), "none");
        final ResourcesListHandler fessToken = new FixedResourcesListHandler(new GatedIndexStatsTool(), "fess_token");

        assertEquals("public", none.handle(contextWithPermissions(Set.of())).get("cacheScope"));
        assertEquals("private", fessToken.handle(contextWithPermissions(Set.of())).get("cacheScope"),
                "a public scope may be shared across callers even on an authenticated endpoint");
    }
}
