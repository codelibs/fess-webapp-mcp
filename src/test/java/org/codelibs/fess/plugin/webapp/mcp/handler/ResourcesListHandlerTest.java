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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.IndexStatsTool;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link ResourcesListHandler}.
 */
public class ResourcesListHandlerTest {

    /**
     * {@code get_index_stats} with the gate disabled, so this file's tests (which are about the
     * resource descriptor's shape, not about the permission gate -- see IndexStatsGateTest for
     * that) never touch the DI container via {@code getRequiredPermissions()}.
     */
    private static final class UngatedIndexStatsTool extends IndexStatsTool {
        @Override
        protected String getIndexStatsPermissions() {
            return "";
        }
    }

    private static final class FixedTtlHandler extends ResourcesListHandler {

        FixedTtlHandler() {
            super(new UngatedIndexStatsTool());
        }

        @Override
        protected long getTtlMs() {
            return 3600000L;
        }

        @Override
        protected String getAuthMode() {
            return "none";
        }
    }

    private McpCallContext contextWithParams(final Map<String, Object> params) {
        return new McpCallContext(null, null, params);
    }

    @Test
    public void testGetMethod() {
        assertEquals("resources/list", new FixedTtlHandler().getMethod());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testListsTheIndexStatsResource() {
        final Map<String, Object> result = new FixedTtlHandler().handle(contextWithParams(Map.of()));

        final List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");
        assertEquals(1, resources.size());
        final Map<String, Object> resource = resources.get(0);
        assertEquals("fess://index/stats", resource.get("uri"));
        assertEquals("Index Statistics", resource.get("name"));
        assertEquals("Fess index statistics and configuration information", resource.get("description"));
        assertEquals("application/json", resource.get("mimeType"));
    }

    @Test
    public void testResultCarriesTtlMsAndCacheScope() {
        final Map<String, Object> result = new FixedTtlHandler().handle(contextWithParams(Map.of()));
        assertEquals(3600000L, result.get("ttlMs"));
        assertEquals("public", result.get("cacheScope"));
    }

    @Test
    public void testTtlIsClampedToZero() {
        final ResourcesListHandler handler = new ResourcesListHandler(new UngatedIndexStatsTool()) {
            @Override
            protected long getTtlMs() {
                return -1L;
            }

            @Override
            protected String getAuthMode() {
                return "none";
            }
        };
        assertEquals(0L, handler.handle(contextWithParams(Map.of())).get("ttlMs"));
    }

    @Test
    public void testCursorIsRejectedWithInvalidParamsAtHttp200() {
        final FixedTtlHandler handler = new FixedTtlHandler();
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of("cursor", "page2"))));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testResultIsMutable() {
        final Map<String, Object> result = new FixedTtlHandler().handle(contextWithParams(Map.of()));
        assertDoesNotThrow(() -> result.put("resultType", "complete"));
    }
}
