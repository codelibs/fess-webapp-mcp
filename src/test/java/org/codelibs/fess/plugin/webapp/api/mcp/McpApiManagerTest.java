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
package org.codelibs.fess.plugin.webapp.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test class for McpApiManager.
 *
 * This test class validates the core functionality of the MCP API methods
 * including initialization, tool listing, resource listing, and prompt listing.
 */
public class McpApiManagerTest {

    private McpApiManager mcpApiManager;

    @BeforeEach
    public void setUp() {
        mcpApiManager = new McpApiManager();
    }

    // handleInitialize()/handleInitialize(Map) and their tests are retired along with the
    // initialize handshake itself (MCP 2026-07-28 deletes it outright and replaces it with the
    // mandatory server/discover method). The replacement's shape -- capabilities, instructions,
    // no logging capability, ttlMs/cacheScope -- is covered by DiscoverHandlerTest; the "initialize
    // is now an error" replacement behaviour is covered by McpDispatcherTest and by
    // testDispatchRpcMethod_InitializeIsNowMethodNotFound below.

    @Test
    public void testHandleListTools() {
        final Map<String, Object> result = mcpApiManager.handleListTools();

        assertNotNull(result, "ListTools result should not be null");
        assertTrue(result.containsKey("tools"), "Result should contain tools key");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertNotNull(tools, "Tools list should not be null");
        assertEquals(4, tools.size(), "Should have 4 tools");

        // Check search tool
        final Map<String, Object> searchTool = tools.get(0);
        assertEquals("search", searchTool.get("name"), "First tool should be search");
        assertTrue(
                ((String) searchTool.get("description")).contains("Search documents via Fess")
                        && ((String) searchTool.get("description")).contains("AND"),
                "Search tool description should contain query syntax info");
        assertNotNull(searchTool.get("inputSchema"), "Search tool should have inputSchema");

        // Check get_index_stats tool
        final Map<String, Object> statsTool = tools.get(1);
        assertEquals("get_index_stats", statsTool.get("name"), "Second tool should be get_index_stats");
        assertEquals("Get index statistics and information", statsTool.get("description"), "Stats tool description");
        assertNotNull(statsTool.get("inputSchema"), "Stats tool should have inputSchema");
    }

    @Test
    public void testHandleListResources() {
        final Map<String, Object> result = mcpApiManager.handleListResources();

        assertNotNull(result, "ListResources result should not be null");
        assertTrue(result.containsKey("resources"), "Result should contain resources key");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");
        assertNotNull(resources, "Resources list should not be null");
        assertEquals(1, resources.size(), "Should have 1 resource");

        final Map<String, Object> resource = resources.get(0);
        assertEquals("fess://index/stats", resource.get("uri"), "Resource URI");
        assertEquals("Index Statistics", resource.get("name"), "Resource name");
        assertNotNull(resource.get("description"), "Resource should have description");
        assertEquals("application/json", resource.get("mimeType"), "Resource mimeType");
    }

    @Test
    public void testHandleListPrompts() {
        final Map<String, Object> result = mcpApiManager.handleListPrompts();

        assertNotNull(result, "ListPrompts result should not be null");
        assertTrue(result.containsKey("prompts"), "Result should contain prompts key");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");
        assertNotNull(prompts, "Prompts list should not be null");
        assertEquals(2, prompts.size(), "Should have 2 prompts");

        // Check basic_search prompt
        final Map<String, Object> basicPrompt = prompts.get(0);
        assertEquals("basic_search", basicPrompt.get("name"), "First prompt should be basic_search");
        assertNotNull(basicPrompt.get("description"), "Basic prompt should have description");
        assertNotNull(basicPrompt.get("arguments"), "Basic prompt should have arguments");

        // Check advanced_search prompt
        final Map<String, Object> advancedPrompt = prompts.get(1);
        assertEquals("advanced_search", advancedPrompt.get("name"), "Second prompt should be advanced_search");
        assertNotNull(advancedPrompt.get("description"), "Advanced prompt should have description");
        assertNotNull(advancedPrompt.get("arguments"), "Advanced prompt should have arguments");
    }

    @Test
    public void testDispatchRpcMethod_InitializeIsNowMethodNotFound() {
        // MCP 2026-07-28 deletes the initialize handshake outright; this legacy dispatch path
        // (still exercised by process() until a later task replaces it) must no longer special-case
        // it. The special-cased "name the supported version" replacement error lives in
        // McpDispatcher, which is what process() will route through once that task lands.
        try {
            mcpApiManager.dispatchRpcMethod("initialize", Map.of());
            fail("Should have thrown McpApiException for retired method initialize");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.MethodNotFound, e.getCode(), "Should be MethodNotFound");
        }
    }

    @Test
    public void testDispatchRpcMethod_ToolsList() {
        final Object result = mcpApiManager.dispatchRpcMethod("tools/list", Map.of());
        assertNotNull(result, "Dispatch result should not be null");
        assertTrue(result instanceof Map, "Result should be a Map");
    }

    @Test
    public void testDispatchRpcMethod_ResourcesList() {
        final Object result = mcpApiManager.dispatchRpcMethod("resources/list", Map.of());
        assertNotNull(result, "Dispatch result should not be null");
        assertTrue(result instanceof Map, "Result should be a Map");
    }

    @Test
    public void testDispatchRpcMethod_PromptsList() {
        final Object result = mcpApiManager.dispatchRpcMethod("prompts/list", Map.of());
        assertNotNull(result, "Dispatch result should not be null");
        assertTrue(result instanceof Map, "Result should be a Map");
    }

    @Test
    public void testDispatchRpcMethod_UnknownMethod() {
        assertThrows(McpApiException.class, () -> mcpApiManager.dispatchRpcMethod("unknown_method", Map.of()));
    }

    @Test
    public void testHandleInvoke_MissingName() {
        assertThrows(McpApiException.class, () -> mcpApiManager.handleInvoke(Map.of()));
    }

    @Test
    public void testHandleInvoke_MissingArguments() {
        assertThrows(McpApiException.class, () -> mcpApiManager.handleInvoke(Map.of("name", "search")));
    }

    @Test
    public void testHandleInvoke_UnknownTool() {
        assertThrows(McpApiException.class, () -> mcpApiManager.handleInvoke(Map.of("name", "unknown_tool", "arguments", Map.of())));
    }

    @Test
    public void testHandleInvoke_EmptyToolName() {
        assertThrows(McpApiException.class, () -> mcpApiManager.handleInvoke(Map.of("name", "", "arguments", Map.of())));
    }

    @Test
    public void testHandleListTools_DetailedSchema() {
        final Map<String, Object> result = mcpApiManager.handleListTools();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        // Verify search tool schema details
        final Map<String, Object> searchTool = tools.get(0);
        @SuppressWarnings("unchecked")
        final Map<String, Object> searchSchema = (Map<String, Object>) searchTool.get("inputSchema");

        assertNotNull(searchSchema, "Search schema should not be null");
        assertEquals("object", searchSchema.get("type"), "Schema type should be object");

        @SuppressWarnings("unchecked")
        final Map<String, Object> searchProperties = (Map<String, Object>) searchSchema.get("properties");
        assertNotNull(searchProperties, "Properties should not be null");
        assertTrue(searchProperties.containsKey("q"), "Should have 'q' property");
        assertTrue(searchProperties.containsKey("start"), "Should have 'start' property");
        assertTrue(searchProperties.containsKey("num"), "Should have 'num' property");
        assertTrue(searchProperties.containsKey("sort"), "Should have 'sort' property");
        assertTrue(searchProperties.containsKey("lang"), "Should have 'lang' property");

        @SuppressWarnings("unchecked")
        final List<String> required = (List<String>) searchSchema.get("required");
        assertNotNull(required, "Required array should not be null");
        assertEquals(1, required.size(), "Should have 1 required field");
        assertEquals("q", required.get(0), "Query 'q' should be required");

        // Verify get_index_stats tool schema
        final Map<String, Object> statsTool = tools.get(1);
        @SuppressWarnings("unchecked")
        final Map<String, Object> statsSchema = (Map<String, Object>) statsTool.get("inputSchema");

        assertNotNull(statsSchema, "Stats schema should not be null");
        assertEquals("object", statsSchema.get("type"), "Stats schema type should be object");

        @SuppressWarnings("unchecked")
        final Map<String, Object> statsProperties = (Map<String, Object>) statsSchema.get("properties");
        assertNotNull(statsProperties, "Stats properties should not be null");
        assertTrue(statsProperties.isEmpty(), "Stats properties should be empty");
    }

    @Test
    public void testHandleListPrompts_DetailedArguments() {
        final Map<String, Object> result = mcpApiManager.handleListPrompts();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");

        // Verify basic_search prompt arguments
        final Map<String, Object> basicPrompt = prompts.get(0);
        assertEquals("basic_search", basicPrompt.get("name"), "Prompt name should be basic_search");
        assertEquals("Perform a basic search with a query string", basicPrompt.get("description"), "Description should match");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> basicArgs = (List<Map<String, Object>>) basicPrompt.get("arguments");
        assertNotNull(basicArgs, "Arguments should not be null");
        assertEquals(1, basicArgs.size(), "Should have 1 argument");

        final Map<String, Object> queryArg = basicArgs.get(0);
        assertEquals("query", queryArg.get("name"), "Argument name should be query");
        assertEquals("The search query", queryArg.get("description"), "Argument description should match");
        assertEquals(true, queryArg.get("required"), "Argument should be required");

        // Verify advanced_search prompt arguments
        final Map<String, Object> advancedPrompt = prompts.get(1);
        assertEquals("advanced_search", advancedPrompt.get("name"), "Prompt name should be advanced_search");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> advancedArgs = (List<Map<String, Object>>) advancedPrompt.get("arguments");
        assertNotNull(advancedArgs, "Arguments should not be null");
        assertEquals(3, advancedArgs.size(), "Should have 3 arguments");

        // Check first argument (query)
        final Map<String, Object> advQueryArg = advancedArgs.get(0);
        assertEquals("query", advQueryArg.get("name"), "First argument should be query");
        assertEquals(true, advQueryArg.get("required"), "Query should be required");

        // Check second argument (sort)
        final Map<String, Object> sortArg = advancedArgs.get(1);
        assertEquals("sort", sortArg.get("name"), "Second argument should be sort");
        assertEquals(false, sortArg.get("required"), "Sort should not be required");

        // Check third argument (num)
        final Map<String, Object> numArg = advancedArgs.get(2);
        assertEquals("num", numArg.get("name"), "Third argument should be num");
        assertEquals(false, numArg.get("required"), "Num should not be required");
    }

    @Test
    public void testHandleGetPrompt_BasicSearch() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "test query"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull(result, "GetPrompt result should not be null");
        assertTrue(result.containsKey("messages"), "Result should contain messages key");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertNotNull(messages, "Messages list should not be null");
        assertEquals(1, messages.size(), "Should have 1 message");

        final Map<String, Object> message = messages.get(0);
        assertEquals("user", message.get("role"), "Message role should be user");

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) message.get("content");
        assertEquals("text", content.get("type"), "Content type should be text");
        assertTrue(content.get("text").toString().contains("test query"), "Content text should contain query");
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("query", "test query", "sort", "score.desc", "num", "10"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull(result, "GetPrompt result should not be null");
        assertTrue(result.containsKey("messages"), "Result should contain messages key");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size(), "Should have 1 message");

        final Map<String, Object> message = messages.get(0);
        assertEquals("user", message.get("role"), "Message role should be user");

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) message.get("content");
        assertEquals("text", content.get("type"), "Content type should be text");
        final String text = content.get("text").toString();
        assertTrue(text.contains("test query"), "Content text should contain query");
        assertTrue(text.contains("score.desc"), "Content text should contain sort");
        assertTrue(text.contains("10"), "Content text should contain num");
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_MinimalArgs() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("query", "minimal query"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull(result, "GetPrompt result should not be null");
        assertTrue(result.containsKey("messages"), "Result should contain messages key");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size(), "Should have 1 message");

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();
        assertTrue(text.contains("minimal query"), "Content text should contain query");
    }

    @Test
    public void testHandleGetPrompt_MissingName() {
        final Map<String, Object> params = new HashMap<>();
        params.put("arguments", Map.of("query", "test"));
        assertThrows(McpApiException.class, () -> mcpApiManager.handleGetPrompt(params));
    }

    @Test
    public void testHandleGetPrompt_EmptyName() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "");
        params.put("arguments", Map.of("query", "test"));
        assertThrows(McpApiException.class, () -> mcpApiManager.handleGetPrompt(params));
    }

    @Test
    public void testHandleGetPrompt_UnknownPrompt() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "unknown_prompt");
        params.put("arguments", Map.of("query", "test"));
        assertThrows(McpApiException.class, () -> mcpApiManager.handleGetPrompt(params));
    }

    @Test
    public void testHandleGetPrompt_MissingRequiredArgument() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of());
        assertThrows(McpApiException.class, () -> mcpApiManager.handleGetPrompt(params));
    }

    @Test
    public void testDispatchRpcMethod_PromptsGet() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "dispatch test"));

        final Object result = mcpApiManager.dispatchRpcMethod("prompts/get", params);

        assertNotNull(result, "dispatchRpcMethod should return non-null result for prompts/get");
        assertTrue(result instanceof Map, "Result should be a Map");

        @SuppressWarnings("unchecked")
        final Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue(resultMap.containsKey("messages"), "Result should contain messages key");
    }

    // ==================== Comprehensive prompts/get Tests ====================

    @Test
    public void testHandleGetPrompt_BasicSearch_WithJapaneseQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "インストール方法"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull(result, "Result should not be null");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        assertTrue(content.get("text").toString().contains("インストール方法"), "Content should contain Japanese query");
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_WithSpecialCharacters() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "test AND (foo OR bar) -exclude \"exact phrase\""));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull(result, "Result should not be null");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        assertTrue(content.get("text").toString().contains("AND"), "Content should contain special characters");
        assertTrue(content.get("text").toString().contains("\"exact phrase\""), "Content should contain special characters");
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_MessageStructure() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "test"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertTrue(result.containsKey("messages"), "Result should have messages key");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size(), "Should have exactly 1 message");

        final Map<String, Object> message = messages.get(0);
        assertEquals("user", message.get("role"), "Message role should be 'user'");
        assertTrue(message.containsKey("content"), "Message should have content");

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) message.get("content");
        assertEquals("text", content.get("type"), "Content type should be 'text'");
        assertNotNull(content.get("text"), "Content text should not be null");
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_NullArguments() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", null);

        try {
            mcpApiManager.handleGetPrompt(params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
        }
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_EmptyQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", ""));

        try {
            mcpApiManager.handleGetPrompt(params);
            fail("Should have thrown McpApiException for empty query");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
            assertTrue(e.getMessage().contains("query"), "Error message should mention query");
        }
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_AllParameters() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "検索テスト");
        arguments.put("sort", "last_modified.desc");
        arguments.put("num", "25");
        params.put("arguments", arguments);

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();

        assertTrue(text.contains("検索テスト"), "Content should contain query");
        assertTrue(text.contains("last_modified.desc"), "Content should contain sort");
        assertTrue(text.contains("25"), "Content should contain num");
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_OnlySortOptional() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "test query");
        arguments.put("sort", "score.desc");
        params.put("arguments", arguments);

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();

        assertTrue(text.contains("test query"), "Content should contain query");
        assertTrue(text.contains("score.desc"), "Content should contain sort");
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_OnlyNumOptional() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "test query");
        arguments.put("num", "50");
        params.put("arguments", arguments);

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();

        assertTrue(text.contains("test query"), "Content should contain query");
        assertTrue(text.contains("50"), "Content should contain num");
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_EmptyOptionalArgs() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "test");
        arguments.put("sort", "");
        arguments.put("num", "");
        params.put("arguments", arguments);

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();

        assertTrue(text.contains("test"), "Content should contain query");
        // Empty optional args should not appear in text
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_NumericNum() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "test");
        arguments.put("num", Integer.valueOf(100));
        params.put("arguments", arguments);

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();

        assertTrue(text.contains("100"), "Content should contain num as string");
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_MissingQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("sort", "score.desc", "num", "10"));

        try {
            mcpApiManager.handleGetPrompt(params);
            fail("Should have thrown McpApiException for missing query");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
            assertTrue(e.getMessage().contains("query"), "Error message should mention query");
        }
    }

    @Test
    public void testHandleGetPrompt_NullName() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", null);
        params.put("arguments", Map.of("query", "test"));

        try {
            mcpApiManager.handleGetPrompt(params);
            fail("Should have thrown McpApiException for null name");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
            assertTrue(e.getMessage().contains("name"), "Error message should mention name");
        }
    }

    @Test
    public void testHandleGetPrompt_ErrorCode_UnknownPrompt() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "nonexistent_prompt");
        params.put("arguments", Map.of("query", "test"));

        try {
            mcpApiManager.handleGetPrompt(params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
            assertTrue(e.getMessage().contains("Unknown prompt"), "Error message should mention unknown prompt");
        }
    }

    @Test
    public void testDispatchRpcMethod_PromptsGet_AdvancedSearch() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "dispatch advanced test");
        arguments.put("sort", "score.desc");
        arguments.put("num", "5");
        params.put("arguments", arguments);

        final Object result = mcpApiManager.dispatchRpcMethod("prompts/get", params);

        assertNotNull(result, "Result should not be null");
        assertTrue(result instanceof Map, "Result should be a Map");
        @SuppressWarnings("unchecked")
        final Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue(resultMap.containsKey("messages"), "Result should contain messages key");
    }

    @Test
    public void testHandleListResources_DetailedFields() {
        final Map<String, Object> result = mcpApiManager.handleListResources();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");

        final Map<String, Object> resource = resources.get(0);
        assertEquals("fess://index/stats", resource.get("uri"), "URI should match");
        assertEquals("Index Statistics", resource.get("name"), "Name should match");
        assertEquals("Fess index statistics and configuration information", resource.get("description"), "Description should not be null");
        assertEquals("application/json", resource.get("mimeType"), "MimeType should be application/json");

        // Verify all expected keys are present
        assertTrue(resource.containsKey("uri"), "Resource should have uri");
        assertTrue(resource.containsKey("name"), "Resource should have name");
        assertTrue(resource.containsKey("description"), "Resource should have description");
        assertTrue(resource.containsKey("mimeType"), "Resource should have mimeType");
    }

    @Test
    public void testHandleReadResource_IndexStats_ValidUri() {
        // This test verifies the URI routing logic works correctly.
        // The actual content generation requires the DI container, which is tested at runtime.
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://index/stats");

        try {
            mcpApiManager.handleReadResource(params);
            // If container is initialized, we would get a valid result
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testHandleReadResource_MissingUri() {
        final Map<String, Object> params = new HashMap<>();
        assertThrows(McpApiException.class, () -> mcpApiManager.handleReadResource(params));
    }

    @Test
    public void testHandleReadResource_EmptyUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "");
        assertThrows(McpApiException.class, () -> mcpApiManager.handleReadResource(params));
    }

    @Test
    public void testHandleReadResource_UnknownResource() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://unknown/resource");
        assertThrows(McpApiException.class, () -> mcpApiManager.handleReadResource(params));
    }

    @Test
    public void testDispatchRpcMethod_ResourcesRead() {
        // This test verifies the dispatch routing works correctly.
        // The actual content generation requires the DI container, which is tested at runtime.
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://index/stats");

        try {
            final Object result = mcpApiManager.dispatchRpcMethod("resources/read", params);
            // If container is initialized, verify the result
            assertNotNull(result, "dispatchRpcMethod should return non-null result for resources/read");
            assertTrue(result instanceof Map, "Result should be a Map");
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    // ==================== Comprehensive resources/read Tests ====================

    @Test
    public void testHandleReadResource_NullUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", null);

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException for null URI");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
            assertTrue(e.getMessage().contains("uri"), "Error message should mention uri");
        }
    }

    @Test
    public void testHandleReadResource_ErrorCode_MissingUri() {
        final Map<String, Object> params = new HashMap<>();

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
            assertTrue(e.getMessage().contains("Missing"), "Error message should mention missing parameter");
        }
    }

    @Test
    public void testHandleReadResource_ErrorCode_UnknownResource() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://unknown/path");

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error (resource not found, MCP 2026-07-28)");
            assertTrue(e.getMessage().contains("Unknown resource"), "Error message should mention unknown resource");
        }
    }

    @Test
    public void testHandleReadResource_InvalidScheme() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "http://example.com/resource");

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException for invalid scheme");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error (resource not found, MCP 2026-07-28)");
        }
    }

    @Test
    public void testHandleReadResource_WhitespaceUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "   ");

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException for whitespace URI");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
        }
    }

    @Test
    public void testHandleReadResource_PartialUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://index");

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException for partial URI");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error (resource not found, MCP 2026-07-28)");
        }
    }

    @Test
    public void testHandleReadResource_CaseSensitiveUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "FESS://INDEX/STATS");

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException for case mismatch URI");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error (resource not found, MCP 2026-07-28)");
        }
    }

    @Test
    public void testHandleReadResource_ExtraPathSegment() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://index/stats/extra");

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException for unknown resource");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error (resource not found, MCP 2026-07-28)");
        }
    }

    @Test
    public void testDispatchRpcMethod_ResourcesRead_MissingParams() {
        try {
            mcpApiManager.dispatchRpcMethod("resources/read", Map.of());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
        }
    }

    @Test
    public void testDispatchRpcMethod_ResourcesRead_UnknownUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://nonexistent/resource");

        try {
            mcpApiManager.dispatchRpcMethod("resources/read", params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error (resource not found, MCP 2026-07-28)");
        }
    }

    @Test
    public void testDispatchRpcMethod_ToolsCall() {
        // Test tools/call method with missing name parameter (should trigger error in handleInvoke)
        try {
            mcpApiManager.dispatchRpcMethod("tools/call", Map.of());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams error");
            assertTrue(e.getMessage().contains("name"), "Error message should mention missing name");
        }
    }

    @Test
    public void testDispatchRpcMethod_AllMethods() {
        // Test all valid methods return non-null results. "initialize" is deliberately excluded:
        // it is retired (see testDispatchRpcMethod_InitializeIsNowMethodNotFound).
        final String[] methods = { "tools/list", "resources/list", "prompts/list" };

        for (final String method : methods) {
            final Object result = mcpApiManager.dispatchRpcMethod(method, Map.of());
            assertNotNull(result, "Result for method '" + method + "' should not be null");
            assertTrue(result instanceof Map, "Result for method '" + method + "' should be a Map");
        }
    }

    @Test
    public void testDispatchRpcMethod_NullMethod() {
        assertThrows(NullPointerException.class, () -> mcpApiManager.dispatchRpcMethod(null, Map.of()));
    }

    @Test
    public void testErrorCodeInException() {
        // Test that different error codes are properly returned in exceptions
        try {
            mcpApiManager.dispatchRpcMethod("unknown_method", Map.of());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.MethodNotFound, e.getCode(), "Error code should be MethodNotFound");
        }

        try {
            mcpApiManager.handleInvoke(Map.of());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Error code should be InvalidParams");
        }
    }

    @Test
    public void testConstructor() {
        final McpApiManager manager = new McpApiManager();
        assertNotNull(manager, "McpApiManager should be created");
    }

    @Test
    public void testSearchToolDescription_ContainsQuerySyntaxInfo() {
        final Map<String, Object> result = mcpApiManager.handleListTools();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        final Map<String, Object> searchTool = tools.get(0);
        final String description = (String) searchTool.get("description");

        assertTrue(description.contains("Lucene"), "Description should mention Lucene");
        assertTrue(description.contains("AND"), "Description should mention AND");
        assertTrue(description.contains("OR"), "Description should mention OR");
        assertTrue(description.contains("phrase"), "Description should mention phrase search");
        assertTrue(description.contains("exclusion") || description.contains("-"), "Description should mention exclusion");
    }

    // ==================== Index Stats Tests ====================

    @Test
    public void testBuildIndexStatsResource_RequiresDIContainer() {
        // buildIndexStatsResource requires ComponentUtil which needs DI container
        try {
            mcpApiManager.buildIndexStatsResource();
            // If container is initialized, we would get a valid result
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testDispatchRpcMethod_ToolsCall_GetIndexStats() {
        // Test tools/call with get_index_stats tool
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "get_index_stats");
        params.put("arguments", Map.of());

        // Without DI container, get_index_stats throws IllegalStateException, which handleInvoke's
        // tool-level error handling (see testHandleInvoke_Search_ToolLevelError_ReturnsIsError) catches
        // and converts into a graceful isError response rather than letting it propagate.
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("tools/call", params);
        assertEquals(true, result.get("isError"), "Should have isError true");
    }

    @Test
    public void testHandleInvoke_GetIndexStats() {
        // Test handleInvoke with get_index_stats tool
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "get_index_stats");
        params.put("arguments", Map.of());

        // Without DI container, get_index_stats throws IllegalStateException, which handleInvoke's
        // tool-level error handling (see testHandleInvoke_Search_ToolLevelError_ReturnsIsError) catches
        // and converts into a graceful isError response rather than letting it propagate.
        final Map<String, Object> result = mcpApiManager.handleInvoke(params);
        assertEquals(true, result.get("isError"), "Should have isError true");
    }

    @Test
    public void testHandleListTools_GetIndexStatsTool() {
        final Map<String, Object> result = mcpApiManager.handleListTools();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        // Find get_index_stats tool
        Map<String, Object> indexStatsTool = null;
        for (final Map<String, Object> tool : tools) {
            if ("get_index_stats".equals(tool.get("name"))) {
                indexStatsTool = tool;
                break;
            }
        }

        assertNotNull(indexStatsTool, "get_index_stats tool should exist");
        assertEquals("get_index_stats", indexStatsTool.get("name"), "Tool name should be get_index_stats");
        assertEquals("Get index statistics and information", indexStatsTool.get("description"), "Tool description");

        @SuppressWarnings("unchecked")
        final Map<String, Object> inputSchema = (Map<String, Object>) indexStatsTool.get("inputSchema");
        assertNotNull(inputSchema, "inputSchema should not be null");
        assertEquals("object", inputSchema.get("type"), "inputSchema type should be object");
    }

    @Test
    public void testHandleListResources_IndexStatsResource() {
        final Map<String, Object> result = mcpApiManager.handleListResources();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");

        assertNotNull(resources, "resources should not be null");
        assertEquals(1, resources.size(), "Should have 1 resource");

        final Map<String, Object> indexStatsResource = resources.get(0);
        assertEquals("fess://index/stats", indexStatsResource.get("uri"), "Resource URI");
        assertEquals("Index Statistics", indexStatsResource.get("name"), "Resource name");
        assertEquals("application/json", indexStatsResource.get("mimeType"), "Resource mimeType");
        assertNotNull(indexStatsResource.get("description"), "Resource should have description");
    }

    @Test
    public void testHandleListTools_SearchToolAnnotations() {
        final Map<String, Object> result = mcpApiManager.handleListTools();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        final Map<String, Object> searchTool = tools.get(0);

        @SuppressWarnings("unchecked")
        final Map<String, Object> annotations = (Map<String, Object>) searchTool.get("annotations");
        assertNotNull(annotations, "Search tool should have annotations");
        assertEquals(true, annotations.get("readOnlyHint"), "Search should be read-only");
        assertEquals(false, annotations.get("destructiveHint"), "Search should not be destructive");
        assertEquals(false, annotations.get("openWorldHint"), "Search should not be open-world");
    }

    @Test
    public void testHandleListTools_IndexStatsToolAnnotations() {
        final Map<String, Object> result = mcpApiManager.handleListTools();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        Map<String, Object> statsTool = null;
        for (final Map<String, Object> tool : tools) {
            if ("get_index_stats".equals(tool.get("name"))) {
                statsTool = tool;
                break;
            }
        }
        assertNotNull(statsTool, "get_index_stats tool should exist");

        @SuppressWarnings("unchecked")
        final Map<String, Object> annotations = (Map<String, Object>) statsTool.get("annotations");
        assertNotNull(annotations, "Stats tool should have annotations");
        assertEquals(true, annotations.get("readOnlyHint"), "Stats should be read-only");
        assertEquals(false, annotations.get("destructiveHint"), "Stats should not be destructive");
    }

    @Test
    public void testHandleInvoke_Search_ToolLevelError_ReturnsIsError() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "search");
        params.put("arguments", Map.of("q", "test"));

        // Without DI container, search will throw IllegalStateException caught by tool-level error handling
        final Map<String, Object> result = mcpApiManager.handleInvoke(params);
        assertEquals(true, result.get("isError"), "Should have isError true");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertNotNull(content, "Should have content");
        assertFalse(content.isEmpty(), "Content should not be empty");
        assertEquals("text", content.get(0).get("type"), "Content type should be text");
        assertTrue(((String) content.get(0).get("text")).startsWith("Error:"), "Error text should contain error info");
    }

    @Test
    public void testHandleListTools_HasSuggestTool() {
        final Map<String, Object> result = mcpApiManager.handleListTools();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        Map<String, Object> suggestTool = null;
        for (final Map<String, Object> tool : tools) {
            if ("suggest".equals(tool.get("name"))) {
                suggestTool = tool;
                break;
            }
        }
        assertNotNull(suggestTool, "suggest tool should exist");
        assertNotNull(suggestTool.get("inputSchema"), "suggest tool should have inputSchema");
        assertNotNull(suggestTool.get("annotations"), "suggest tool should have annotations");

        @SuppressWarnings("unchecked")
        final Map<String, Object> schema = (Map<String, Object>) suggestTool.get("inputSchema");
        @SuppressWarnings("unchecked")
        final List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("q"), "q should be required");
    }

    @Test
    public void testHandleInvoke_Suggest_MissingQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "suggest");
        params.put("arguments", Map.of());

        try {
            mcpApiManager.handleInvoke(params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    @Test
    public void testHandleListTools_HasGetDocumentTool() {
        final Map<String, Object> result = mcpApiManager.handleListTools();
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        Map<String, Object> getDocTool = null;
        for (final Map<String, Object> tool : tools) {
            if ("get_document".equals(tool.get("name"))) {
                getDocTool = tool;
                break;
            }
        }
        assertNotNull(getDocTool, "get_document tool should exist");

        @SuppressWarnings("unchecked")
        final Map<String, Object> schema = (Map<String, Object>) getDocTool.get("inputSchema");
        @SuppressWarnings("unchecked")
        final List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("doc_id"), "doc_id should be required");
    }

    @Test
    public void testHandleInvoke_GetDocument_MissingDocId() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "get_document");
        params.put("arguments", Map.of());

        try {
            mcpApiManager.handleInvoke(params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    // ==================== Pagination / cursor param Tests ====================

    @Test
    public void testHandleListTools_AcceptsCursorParam() {
        final Object result = mcpApiManager.dispatchRpcMethod("tools/list", Map.of("cursor", "some_cursor"));
        assertNotNull(result, "Should handle cursor param gracefully");
    }

    @Test
    public void testHandleListResources_AcceptsCursorParam() {
        final Object result = mcpApiManager.dispatchRpcMethod("resources/list", Map.of("cursor", "some_cursor"));
        assertNotNull(result, "Should handle cursor param gracefully");
    }

    @Test
    public void testHandleListPrompts_AcceptsCursorParam() {
        final Object result = mcpApiManager.dispatchRpcMethod("prompts/list", Map.of("cursor", "some_cursor"));
        assertNotNull(result, "Should handle cursor param gracefully");
    }

    // ==================== Resource Templates Tests ====================

    @Test
    public void testDispatchRpcMethod_ResourcesTemplatesList() {
        final Object result = mcpApiManager.dispatchRpcMethod("resources/templates/list", Map.of());
        assertNotNull(result, "Result should not be null");
        assertTrue(result instanceof Map, "Result should be a Map");

        @SuppressWarnings("unchecked")
        final Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue(resultMap.containsKey("resourceTemplates"), "Result should have resourceTemplates key");

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> templates = (List<Map<String, Object>>) resultMap.get("resourceTemplates");
        assertFalse(templates.isEmpty(), "Templates list should not be empty");

        final Map<String, Object> template = templates.get(0);
        assertNotNull(template.get("uriTemplate"), "Template should have uriTemplate");
        assertEquals("fess://document/{doc_id}", template.get("uriTemplate"));
        assertNotNull(template.get("name"), "Template should have name");
    }

    @Test
    public void testHandleReadResource_DocumentUri_RequiresDIContainer() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://document/test_doc_id");

        try {
            mcpApiManager.handleReadResource(params);
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        } catch (final McpApiException e) {
            // InvalidParams (resource not found) is also acceptable if container is available but doc doesn't exist
        }
    }

    @Test
    public void testHandleReadResource_DocumentUri_EmptyDocId() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://document/");

        try {
            mcpApiManager.handleReadResource(params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    // ==================== completion/complete Tests ====================

    @Test
    public void testHandleComplete_MissingRef() {
        try {
            mcpApiManager.dispatchRpcMethod("completion/complete", Map.of());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    @Test
    public void testHandleComplete_MissingArgument() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "basic_search"));

        try {
            mcpApiManager.dispatchRpcMethod("completion/complete", params);
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    @Test
    public void testHandleComplete_EmptyValue() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "basic_search"));
        params.put("argument", Map.of("name", "query", "value", ""));

        final Object result = mcpApiManager.dispatchRpcMethod("completion/complete", params);
        assertNotNull(result, "Result should not be null");

        @SuppressWarnings("unchecked")
        final Map<String, Object> resultMap = (Map<String, Object>) result;
        assertNotNull(resultMap.get("completion"), "Should have completion");

        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) resultMap.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertTrue(values.isEmpty(), "Values should be empty for empty input");
        assertEquals(false, completion.get("hasMore"), "hasMore should be false");
    }

    @Test
    public void testHandleComplete_WithValue_RequiresDIContainer() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "basic_search"));
        params.put("argument", Map.of("name", "query", "value", "test"));

        try {
            mcpApiManager.dispatchRpcMethod("completion/complete", params);
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container");
        }
    }

    // ===== Batch request tests =====

    @Test
    public void testProcessBatchRequests() {
        // "initialize" is retired (MCP 2026-07-28); use two live methods instead so this test
        // still exercises real batch mechanics rather than the removed handshake.
        final List<Map<String, Object>> requests =
                List.of(Map.of("jsonrpc", "2.0", "id", 1, "method", "resources/list", "params", Map.of()),
                        Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()));

        final List<Map<String, Object>> responses = mcpApiManager.processBatchRequests(requests);

        assertEquals(2, responses.size(), "Should have 2 responses");
        assertEquals(1, responses.get(0).get("id"), "First response id should be 1");
        assertEquals(2, responses.get(1).get("id"), "Second response id should be 2");
        assertNotNull(responses.get(0).get("result"), "First response should have result");
        assertNotNull(responses.get(1).get("result"), "Second response should have result");
    }

    @Test
    public void testProcessBatchRequests_WithNotification() {
        final Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");

        final List<Map<String, Object>> requests = new ArrayList<>();
        requests.add(notification);
        // "initialize" is retired (MCP 2026-07-28); prompts/list is a live method instead.
        requests.add(Map.of("jsonrpc", "2.0", "id", 1, "method", "prompts/list", "params", Map.of()));

        final List<Map<String, Object>> responses = mcpApiManager.processBatchRequests(requests);

        assertEquals(1, responses.size(), "Should have 1 response (notification excluded)");
        assertEquals(1, responses.get(0).get("id"), "Response id should be 1");
    }

    @Test
    public void testProcessBatchRequests_WithError() {
        final List<Map<String, Object>> requests =
                List.of(Map.of("jsonrpc", "2.0", "id", 1, "method", "unknown_method", "params", Map.of()));

        final List<Map<String, Object>> responses = mcpApiManager.processBatchRequests(requests);

        assertEquals(1, responses.size(), "Should have 1 response");
        assertNotNull(responses.get(0).get("error"), "Response should have error");
    }

    @Test
    public void testProcessBatchRequests_WithInvalidRequest() {
        final List<Map<String, Object>> requests = new ArrayList<>();
        requests.add(Map.of("jsonrpc", "1.0", "id", 1, "method", "initialize"));

        final List<Map<String, Object>> responses = mcpApiManager.processBatchRequests(requests);

        assertEquals(1, responses.size(), "Should have 1 error response");
        assertNotNull(responses.get(0).get("error"), "Response should have error");
    }

    // The old "initialize protocol version negotiation" test group is retired along with
    // handleInitialize itself: MCP 2026-07-28 has exactly one protocol version and no
    // negotiation. See the comment above testHandleListTools for where the replacement coverage
    // lives.

    // ==================== completion/complete (Task C) tests ====================

    @Test
    public void testHandleComplete_AdvancedSearchSort_PrefixFilter() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "advanced_search"));
        params.put("argument", Map.of("name", "sort", "value", "score"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertEquals(2, values.size(), "Should return 2 score entries");
        assertTrue(values.contains("score.desc"));
        assertTrue(values.contains("score.asc"));
        assertEquals(false, completion.get("hasMore"));
    }

    @Test
    public void testHandleComplete_AdvancedSearchSort_EmptyValueReturnsAll() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "advanced_search"));
        params.put("argument", Map.of("name", "sort", "value", ""));

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertEquals(6, values.size(), "Empty prefix should return all 6 sort values");
    }

    @Test
    public void testHandleComplete_AdvancedSearchNum_EmptyValues() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "advanced_search"));
        params.put("argument", Map.of("name", "num", "value", "1"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertTrue(values.isEmpty(), "num argument should produce no completions");
        assertEquals(false, completion.get("hasMore"));
    }

    @Test
    public void testHandleComplete_UnknownPromptName() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "unknown_prompt"));
        params.put("argument", Map.of("name", "query", "value", "hello"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertTrue(values.isEmpty(), "Unknown prompt should produce no completions");
    }

    @Test
    public void testHandleComplete_RefResourceReturnsEmpty() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/resource", "uri", "fess://doc/123"));
        params.put("argument", Map.of("name", "doc_id", "value", "abc"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertTrue(values.isEmpty(), "ref/resource should produce no completions");
    }

    @Test
    public void testHandleComplete_UnknownRefTypeReturnsEmpty() {
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/unknown"));
        params.put("argument", Map.of("name", "x", "value", "y"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertTrue(values.isEmpty(), "Unknown ref.type should produce no completions");
    }

    // ==================== Regression: logging/setLevel removed ====================

    @Test
    public void testDispatchRpcMethod_LoggingSetLevel_IsMethodNotFound() {
        // Regression: logging capability and logging/setLevel handler were removed.
        // The server must now reject logging/setLevel with MethodNotFound.
        try {
            mcpApiManager.dispatchRpcMethod("logging/setLevel", Map.of("level", "debug"));
            fail("Should have thrown McpApiException for removed method logging/setLevel");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.MethodNotFound, e.getCode(), "Should be MethodNotFound");
        }
    }

    // The old "initialize protocolVersion type robustness" test group is retired along with
    // handleInitialize itself -- there is no protocolVersion parameter to be robust about any
    // more.

    // ==================== completion/complete robustness ====================

    @Test
    public void testHandleComplete_NullArgumentValue_TreatedAsEmpty() {
        // When argument.value is absent (null), treat it like an empty prefix.
        // For the sort completion this should return all static SORT_VALUES.
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "advanced_search"));
        final Map<String, Object> arg = new HashMap<>();
        arg.put("name", "sort");
        // intentionally no "value" key
        params.put("argument", arg);

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertEquals(6, values.size(), "Null value should behave like empty prefix and return all 6 sort values");
    }

    @Test
    public void testHandleComplete_EmptyArgumentMap_BasicSearchQuery() {
        // argument is an empty map: name is null, so no handler matches for basic_search
        // (handler branches on argName.equals("query")). Expect empty completion, no DI access.
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "basic_search"));
        params.put("argument", Map.of());

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertTrue(values.isEmpty(), "Empty argument map should yield no completions without touching DI");
        assertEquals(0, ((Number) completion.get("total")).intValue());
        assertEquals(false, completion.get("hasMore"));
    }

    @Test
    public void testHandleComplete_SortPrefix_NoMatch() {
        // A prefix that matches no SORT_VALUES must yield an empty list (not all values).
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "advanced_search"));
        params.put("argument", Map.of("name", "sort", "value", "zzz"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.dispatchRpcMethod("completion/complete", params);
        @SuppressWarnings("unchecked")
        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) completion.get("values");
        assertTrue(values.isEmpty(), "Unknown prefix should produce empty values");
        assertEquals(false, completion.get("hasMore"));
    }

    @Test
    public void testBuildCompletionResult_Caps100_ValuesAndReflectsHasMore() {
        // buildCompletionResult must cap values at 100 and ensure hasMore reflects
        // the fact that the reported total exceeds the capped list size.
        final List<String> over = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            over.add("v" + i);
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.buildCompletionResult(over, 150, false).get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) result.get("values");
        assertEquals(100, values.size(), "Values must be capped at 100");
        assertEquals(150, ((Number) result.get("total")).intValue(), "Total should be the original total");
        assertEquals(true, result.get("hasMore"), "hasMore must be true when total exceeds cap");
    }

    @Test
    public void testBuildCompletionResult_ExactlyAtCap_HasMoreFalse() {
        final List<String> exact = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            exact.add("v" + i);
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.buildCompletionResult(exact, 100, false).get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) result.get("values");
        assertEquals(100, values.size(), "Exactly 100 values must remain 100");
        assertEquals(100, ((Number) result.get("total")).intValue());
        assertEquals(false, result.get("hasMore"), "hasMore must be false when total equals capped size");
    }

    @Test
    public void testBuildCompletionResult_TotalLessThanValues_NormalizesTotal() {
        // Defensive: if caller passes total < values.size(), total should be normalized
        // to at least values.size() so the envelope remains consistent.
        final List<String> three = List.of("a", "b", "c");
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.buildCompletionResult(three, 0, false).get("completion");
        assertEquals(3, ((Number) result.get("total")).intValue(), "Total must be at least the number of values");
    }

    @Test
    public void testCreateErrorResponse() {
        final Map<String, Object> response = mcpApiManager.createErrorResponse(1, ErrorCode.MethodNotFound, "test error");

        assertEquals("2.0", response.get("jsonrpc"));
        assertEquals(1, response.get("id"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(-32601, error.get("code"));
        assertEquals("test error", error.get("message"));
    }
}
