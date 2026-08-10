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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link ToolsListHandler}.
 */
public class ToolsListHandlerTest {

    /** A minimal {@link McpTool} double, so this suite never touches the DI container. */
    private static final class StubTool implements McpTool {

        private final String name;

        StubTool(final String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "desc:" + name;
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public Map<String, Object> getAnnotations() {
            return Map.of("readOnlyHint", true);
        }

        @Override
        public Set<String> getRequiredPermissions() {
            return Set.of();
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }

    private static final class FixedTtlHandler extends ToolsListHandler {

        FixedTtlHandler(final List<McpTool> tools) {
            super(tools);
        }

        @Override
        protected long getTtlMs() {
            return 3600000L;
        }
    }

    private McpCallContext contextWithParams(final Map<String, Object> params) {
        return new McpCallContext(null, null, params);
    }

    @Test
    public void testGetMethod() {
        assertEquals("tools/list", new FixedTtlHandler(List.of()).getMethod());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testListsEachToolByNameDescriptionSchemaAndAnnotations() {
        final ToolsListHandler handler = new FixedTtlHandler(List.of(new StubTool("search"), new StubTool("suggest")));

        final Map<String, Object> result = handler.handle(contextWithParams(Map.of()));

        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals(2, tools.size());
        assertEquals("search", tools.get(0).get("name"));
        assertEquals("desc:search", tools.get(0).get("description"));
        assertEquals(Map.of("type", "object"), tools.get(0).get("inputSchema"));
        assertEquals(Map.of("readOnlyHint", true), tools.get(0).get("annotations"));
        assertEquals("suggest", tools.get(1).get("name"), "tool order must be preserved");
    }

    @Test
    public void testResultCarriesTtlMsAndPublicCacheScope() {
        final Map<String, Object> result = new FixedTtlHandler(List.of()).handle(contextWithParams(Map.of()));
        assertEquals(3600000L, result.get("ttlMs"));
        assertEquals("public", result.get("cacheScope"));
    }

    @Test
    public void testTtlIsClampedToZero() {
        final ToolsListHandler handler = new ToolsListHandler(List.of()) {
            @Override
            protected long getTtlMs() {
                return -5L;
            }
        };
        assertEquals(0L, handler.handle(contextWithParams(Map.of())).get("ttlMs"));
    }

    @Test
    public void testCursorIsRejectedWithInvalidParamsAtHttp200() {
        final ToolsListHandler handler = new FixedTtlHandler(List.of());

        final McpError error = org.junit.jupiter.api.Assertions.assertThrows(McpError.class,
                () -> handler.handle(contextWithParams(Map.of("cursor", "x"))));

        assertEquals(200, error.getHttpStatus());
        assertEquals(org.codelibs.fess.plugin.webapp.mcp.ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testResultIsMutable() {
        final Map<String, Object> result = new FixedTtlHandler(List.of()).handle(contextWithParams(Map.of()));
        assertDoesNotThrow(() -> result.put("resultType", "complete"));
    }

    @Test
    public void testDoesNotSetResultTypeOrServerInfo() {
        final Map<String, Object> result = new FixedTtlHandler(List.of()).handle(contextWithParams(Map.of()));
        assertFalse(result.containsKey("resultType"));
        assertFalse(result.containsKey("_meta"));
    }

    @Test
    public void testNoArgConstructorDefaultsToTheStandardFourTools() {
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools =
                (List<Map<String, Object>>) new FixedTtlHandlerNoArg().handle(contextWithParams(Map.of())).get("tools");
        assertNotNull(tools);
        assertTrue(tools.size() >= 4, "the default tool set must include at least the 4 built-in tools");
    }

    /** Exercises the real no-arg constructor's default tool set without touching the DI container. */
    private static final class FixedTtlHandlerNoArg extends ToolsListHandler {

        @Override
        protected long getTtlMs() {
            return 3600000L;
        }
    }
}
