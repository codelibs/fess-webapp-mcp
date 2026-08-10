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

import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link CompletionHandler}.
 */
public class CompletionHandlerTest {

    private final CompletionHandler handler = new CompletionHandler();

    private McpCallContext contextWithParams(final Map<String, Object> params) {
        return new McpCallContext(null, null, params);
    }

    @Test
    public void testGetMethod() {
        assertEquals("completion/complete", handler.getMethod());
    }

    @Test
    public void testMissingRefIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of())));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testMissingArgumentIsInvalidParams() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/prompt", "name", "basic_search"));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testEmptyValueReturnsEmptyCompletionsWithoutTouchingTheDiContainer() {
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "basic_search"), "argument", Map.of("name", "query", "value", ""));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertTrue(((List<String>) completion.get("values")).isEmpty());
        assertEquals(false, completion.get("hasMore"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchSortPrefixFilter() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/prompt", "name", "advanced_search"), "argument",
                Map.of("name", "sort", "value", "score"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        final List<String> values = (List<String>) completion.get("values");
        assertEquals(2, values.size());
        assertTrue(values.contains("score.desc"));
        assertTrue(values.contains("score.asc"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUnknownRefTypeReturnsEmptyCompletions() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/unknown"), "argument", Map.of("name", "x", "value", "y"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertTrue(((List<String>) completion.get("values")).isEmpty());
    }

    @Test
    public void testDoesNotSetTtlMsOrCacheScope() {
        // CompleteResult is not a CacheableResult in the 2026-07-28 schema.
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/unknown"), "argument", Map.of());
        final Map<String, Object> result = handler.handle(contextWithParams(params));
        assertFalse(result.containsKey("ttlMs"));
        assertFalse(result.containsKey("cacheScope"));
    }

    @Test
    public void testResultIsMutable() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/unknown"), "argument", Map.of());
        final Map<String, Object> result = handler.handle(contextWithParams(params));
        assertDoesNotThrow(() -> result.put("resultType", "complete"));
    }
}
