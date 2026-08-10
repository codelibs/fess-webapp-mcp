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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link PromptsGetHandler}.
 */
public class PromptsGetHandlerTest {

    private final PromptsGetHandler handler = new PromptsGetHandler();

    private McpCallContext contextWithParams(final Map<String, Object> params) {
        return new McpCallContext(null, null, params);
    }

    @Test
    public void testGetMethod() {
        assertEquals("prompts/get", handler.getMethod());
    }

    @Test
    public void testMissingNameIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of())));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("name"));
    }

    @Test
    public void testUnknownPromptIsInvalidParams() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "unknown_prompt");
        params.put("arguments", Map.of("query", "test"));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("Unknown prompt"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testBasicSearchSubstitutesTheQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "test query"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size());
        final Map<String, Object> message = messages.get(0);
        assertEquals("user", message.get("role"));
        final Map<String, Object> content = (Map<String, Object>) message.get("content");
        assertEquals("text", content.get("type"));
        assertTrue(content.get("text").toString().contains("test query"));
    }

    @Test
    public void testBasicSearchMissingQueryIsInvalidParams() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of());
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("query"));
    }

    @Test
    public void testBasicSearchNullArgumentsTreatedAsEmpty() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", null);
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchIncludesOptionalSortAndNum() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("query", "test query", "sort", "score.desc", "num", "10"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();
        assertTrue(text.contains("test query"));
        assertTrue(text.contains("score.desc"));
        assertTrue(text.contains("10"));
    }

    @Test
    public void testAdvancedSearchMissingQueryIsInvalidParams() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("sort", "score.desc"));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("query"));
    }

    @Test
    public void testDoesNotSetTtlMsOrCacheScope() {
        // GetPromptResult is not a CacheableResult in the 2026-07-28 schema.
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "x"));
        final Map<String, Object> result = handler.handle(contextWithParams(params));
        assertFalse(result.containsKey("ttlMs"));
        assertFalse(result.containsKey("cacheScope"));
    }

    @Test
    public void testResultIsMutable() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "x"));
        final Map<String, Object> result = handler.handle(contextWithParams(params));
        assertDoesNotThrow(() -> result.put("resultType", "complete"));
    }
}
