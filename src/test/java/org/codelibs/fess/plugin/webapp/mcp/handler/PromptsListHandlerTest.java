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
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link PromptsListHandler}.
 */
public class PromptsListHandlerTest {

    private static final class FixedTtlHandler extends PromptsListHandler {

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
        assertEquals("prompts/list", new FixedTtlHandler().getMethod());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testListsBasicAndAdvancedSearchPrompts() {
        final Map<String, Object> result = new FixedTtlHandler().handle(contextWithParams(Map.of()));

        final List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");
        assertEquals(2, prompts.size());

        final Map<String, Object> basic = prompts.get(0);
        assertEquals("basic_search", basic.get("name"));
        assertEquals("Perform a basic search with a query string", basic.get("description"));
        final List<Map<String, Object>> basicArgs = (List<Map<String, Object>>) basic.get("arguments");
        assertEquals(1, basicArgs.size());
        assertEquals("query", basicArgs.get(0).get("name"));
        assertEquals("The search query", basicArgs.get(0).get("description"));
        assertEquals(true, basicArgs.get(0).get("required"));

        final Map<String, Object> advanced = prompts.get(1);
        assertEquals("advanced_search", advanced.get("name"));
        assertEquals("Perform an advanced search with filters and sorting", advanced.get("description"));
        final List<Map<String, Object>> advancedArgs = (List<Map<String, Object>>) advanced.get("arguments");
        assertEquals(3, advancedArgs.size());
        assertEquals("query", advancedArgs.get(0).get("name"));
        assertEquals(true, advancedArgs.get(0).get("required"));
        assertEquals("sort", advancedArgs.get(1).get("name"));
        assertEquals(false, advancedArgs.get(1).get("required"));
        assertEquals("num", advancedArgs.get(2).get("name"));
        assertEquals(false, advancedArgs.get(2).get("required"));
    }

    @Test
    public void testResultCarriesTtlMsAndPublicCacheScope() {
        final Map<String, Object> result = new FixedTtlHandler().handle(contextWithParams(Map.of()));
        assertEquals(3600000L, result.get("ttlMs"));
        assertEquals("public", result.get("cacheScope"));
    }

    @Test
    public void testTtlIsClampedToZero() {
        final PromptsListHandler handler = new PromptsListHandler() {
            @Override
            protected long getTtlMs() {
                return -1L;
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
