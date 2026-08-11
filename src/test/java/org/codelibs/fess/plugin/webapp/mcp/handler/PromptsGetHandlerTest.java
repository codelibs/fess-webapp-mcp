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
    public void testEmptyNameIsInvalidParams() {
        // The isEmpty() half of the name guard, which nothing exercised: the test above covers
        // only the absent/non-String half, so deleting {@code || name.isEmpty()} left the suite
        // green while {"name": ""} fell through the switch and was reported as
        // "Unknown prompt: " -- a different message, for what is really a malformed request.
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "");
        params.put("arguments", Map.of("query", "test"));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals("Missing required parameter: name", error.getMessage(),
                "an empty name is a missing name, not an unknown prompt: " + error.getMessage());
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
    public void testBasicSearchEmptyQueryIsInvalidParams() {
        // The isEmpty() half of the basic_search query guard. This is the one guard in this
        // family whose loss a caller would actually notice: without it, {"query": ""} does not
        // fail at all -- it returns a perfectly well-formed prompt reading "Please search for: "
        // with nothing after the colon, which the model then acts on.
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", ""));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("query"), "the message must name the argument: " + error.getMessage());
    }

    @Test
    public void testAdvancedSearchEmptyQueryIsInvalidParams() {
        // Same guard, duplicated in buildAdvancedSearchPrompt, so it needs its own assertion: the
        // two copies can be broken independently. Without it the prompt is emitted with a bare
        // "Query: " line -- and, when the caller also sent sort/num, with those lines populated,
        // which makes it look even more like a legitimate request.
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("query", "", "sort", "score.desc", "num", "10"));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("query"), "the message must name the argument: " + error.getMessage());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchOmitsAnEmptySortAndNumEntirely() {
        // The isEmpty() halves of the two optional-argument guards, asserted as ABSENCE.
        //
        // testAdvancedSearchIncludesOptionalSortAndNum pins the populated direction, and nothing
        // pinned this one: dropping {@code && !sort.toString().isEmpty()} appends a dangling
        // "Sort:" label with no value after it (likewise "Number of results:"), instructing the
        // model to sort by nothing. A test that only checked the query still came through would
        // pass with both labels present, which is why these are assertFalse on the labels rather
        // than assertTrue on the query.
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("query", "test query", "sort", "", "num", ""));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();
        assertTrue(text.contains("Query: test query"), "the query must still be substituted");
        assertFalse(text.contains("Sort:"), "an empty sort must not emit a dangling label: " + text);
        assertFalse(text.contains("Number of results:"), "an empty num must not emit a dangling label: " + text);
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

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchAcceptsNonStringNum() {
        // Migrated from the retired McpApiManagerTest#testHandleGetPrompt_AdvancedSearch_NumericNum:
        // arguments.get("num") is read as a raw Object and only later toString()'d, so a JSON
        // number (decoded as an Integer, not a String) must still be substituted correctly.
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "test");
        arguments.put("num", Integer.valueOf(100));
        params.put("arguments", arguments);

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        assertTrue(content.get("text").toString().contains("100"), "Content should contain num as string");
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
