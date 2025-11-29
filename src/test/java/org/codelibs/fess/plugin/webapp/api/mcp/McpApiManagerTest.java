/*
 * Copyright 2012-2024 CodeLibs Project and the Others.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for McpApiManager.
 *
 * This test class validates the core functionality of the MCP API methods
 * including initialization, tool listing, resource listing, and prompt listing.
 */
public class McpApiManagerTest {

    private McpApiManager mcpApiManager;

    @Before
    public void setUp() {
        mcpApiManager = new McpApiManager();
    }

    @Test
    public void testHandleInitialize() {
        final Map<String, Object> result = mcpApiManager.handleInitialize();

        assertNotNull("Initialize result should not be null", result);
        assertEquals("Protocol version should be 2024-11-05", "2024-11-05", result.get("protocolVersion"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
        assertNotNull("Capabilities should not be null", capabilities);
        assertTrue("Capabilities should include tools", capabilities.containsKey("tools"));
        assertTrue("Capabilities should include resources", capabilities.containsKey("resources"));
        assertTrue("Capabilities should include prompts", capabilities.containsKey("prompts"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertNotNull("ServerInfo should not be null", serverInfo);
        assertEquals("Server name should be fess-mcp-server", "fess-mcp-server", serverInfo.get("name"));
        assertEquals("Server version should be 1.0.0", "1.0.0", serverInfo.get("version"));
    }

    @Test
    public void testHandleListTools() {
        final Map<String, Object> result = mcpApiManager.handleListTools();

        assertNotNull("ListTools result should not be null", result);
        assertTrue("Result should contain tools key", result.containsKey("tools"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertNotNull("Tools list should not be null", tools);
        assertEquals("Should have 2 tools", 2, tools.size());

        // Check search tool
        final Map<String, Object> searchTool = tools.get(0);
        assertEquals("First tool should be search", "search", searchTool.get("name"));
        assertTrue("Search tool description should contain query syntax info",
                ((String) searchTool.get("description")).contains("Search documents via Fess")
                        && ((String) searchTool.get("description")).contains("AND"));
        assertNotNull("Search tool should have inputSchema", searchTool.get("inputSchema"));

        // Check get_index_stats tool
        final Map<String, Object> statsTool = tools.get(1);
        assertEquals("Second tool should be get_index_stats", "get_index_stats", statsTool.get("name"));
        assertEquals("Stats tool description", "Get index statistics and information", statsTool.get("description"));
        assertNotNull("Stats tool should have inputSchema", statsTool.get("inputSchema"));
    }

    @Test
    public void testHandleListResources() {
        final Map<String, Object> result = mcpApiManager.handleListResources();

        assertNotNull("ListResources result should not be null", result);
        assertTrue("Result should contain resources key", result.containsKey("resources"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");
        assertNotNull("Resources list should not be null", resources);
        assertEquals("Should have 1 resource", 1, resources.size());

        final Map<String, Object> resource = resources.get(0);
        assertEquals("Resource URI", "fess://index/stats", resource.get("uri"));
        assertEquals("Resource name", "Index Statistics", resource.get("name"));
        assertNotNull("Resource should have description", resource.get("description"));
        assertEquals("Resource mimeType", "application/json", resource.get("mimeType"));
    }

    @Test
    public void testHandleListPrompts() {
        final Map<String, Object> result = mcpApiManager.handleListPrompts();

        assertNotNull("ListPrompts result should not be null", result);
        assertTrue("Result should contain prompts key", result.containsKey("prompts"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");
        assertNotNull("Prompts list should not be null", prompts);
        assertEquals("Should have 2 prompts", 2, prompts.size());

        // Check basic_search prompt
        final Map<String, Object> basicPrompt = prompts.get(0);
        assertEquals("First prompt should be basic_search", "basic_search", basicPrompt.get("name"));
        assertNotNull("Basic prompt should have description", basicPrompt.get("description"));
        assertNotNull("Basic prompt should have arguments", basicPrompt.get("arguments"));

        // Check advanced_search prompt
        final Map<String, Object> advancedPrompt = prompts.get(1);
        assertEquals("Second prompt should be advanced_search", "advanced_search", advancedPrompt.get("name"));
        assertNotNull("Advanced prompt should have description", advancedPrompt.get("description"));
        assertNotNull("Advanced prompt should have arguments", advancedPrompt.get("arguments"));
    }

    @Test
    public void testDispatchRpcMethod_Initialize() {
        final Object result = mcpApiManager.dispatchRpcMethod("initialize", Map.of());
        assertNotNull("Dispatch result should not be null", result);
        assertTrue("Result should be a Map", result instanceof Map);
    }

    @Test
    public void testDispatchRpcMethod_ToolsList() {
        final Object result = mcpApiManager.dispatchRpcMethod("tools/list", Map.of());
        assertNotNull("Dispatch result should not be null", result);
        assertTrue("Result should be a Map", result instanceof Map);
    }

    @Test
    public void testDispatchRpcMethod_ResourcesList() {
        final Object result = mcpApiManager.dispatchRpcMethod("resources/list", Map.of());
        assertNotNull("Dispatch result should not be null", result);
        assertTrue("Result should be a Map", result instanceof Map);
    }

    @Test
    public void testDispatchRpcMethod_PromptsList() {
        final Object result = mcpApiManager.dispatchRpcMethod("prompts/list", Map.of());
        assertNotNull("Dispatch result should not be null", result);
        assertTrue("Result should be a Map", result instanceof Map);
    }

    @Test(expected = McpApiException.class)
    public void testDispatchRpcMethod_UnknownMethod() {
        mcpApiManager.dispatchRpcMethod("unknown_method", Map.of());
    }

    @Test(expected = McpApiException.class)
    public void testHandleInvoke_MissingName() {
        mcpApiManager.handleInvoke(Map.of());
    }

    @Test(expected = McpApiException.class)
    public void testHandleInvoke_MissingArguments() {
        mcpApiManager.handleInvoke(Map.of("name", "search"));
    }

    @Test(expected = McpApiException.class)
    public void testHandleInvoke_UnknownTool() {
        mcpApiManager.handleInvoke(Map.of("name", "unknown_tool", "arguments", Map.of()));
    }

    @Test(expected = McpApiException.class)
    public void testHandleInvoke_EmptyToolName() {
        mcpApiManager.handleInvoke(Map.of("name", "", "arguments", Map.of()));
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

        assertNotNull("Search schema should not be null", searchSchema);
        assertEquals("Schema type should be object", "object", searchSchema.get("type"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> searchProperties = (Map<String, Object>) searchSchema.get("properties");
        assertNotNull("Properties should not be null", searchProperties);
        assertTrue("Should have 'q' property", searchProperties.containsKey("q"));
        assertTrue("Should have 'start' property", searchProperties.containsKey("start"));
        assertTrue("Should have 'num' property", searchProperties.containsKey("num"));
        assertTrue("Should have 'sort' property", searchProperties.containsKey("sort"));
        assertTrue("Should have 'lang' property", searchProperties.containsKey("lang"));

        @SuppressWarnings("unchecked")
        final List<String> required = (List<String>) searchSchema.get("required");
        assertNotNull("Required array should not be null", required);
        assertEquals("Should have 1 required field", 1, required.size());
        assertEquals("Query 'q' should be required", "q", required.get(0));

        // Verify get_index_stats tool schema
        final Map<String, Object> statsTool = tools.get(1);
        @SuppressWarnings("unchecked")
        final Map<String, Object> statsSchema = (Map<String, Object>) statsTool.get("inputSchema");

        assertNotNull("Stats schema should not be null", statsSchema);
        assertEquals("Stats schema type should be object", "object", statsSchema.get("type"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> statsProperties = (Map<String, Object>) statsSchema.get("properties");
        assertNotNull("Stats properties should not be null", statsProperties);
        assertTrue("Stats properties should be empty", statsProperties.isEmpty());
    }

    @Test
    public void testHandleListPrompts_DetailedArguments() {
        final Map<String, Object> result = mcpApiManager.handleListPrompts();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");

        // Verify basic_search prompt arguments
        final Map<String, Object> basicPrompt = prompts.get(0);
        assertEquals("Prompt name should be basic_search", "basic_search", basicPrompt.get("name"));
        assertEquals("Description should match", "Perform a basic search with a query string", basicPrompt.get("description"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> basicArgs = (List<Map<String, Object>>) basicPrompt.get("arguments");
        assertNotNull("Arguments should not be null", basicArgs);
        assertEquals("Should have 1 argument", 1, basicArgs.size());

        final Map<String, Object> queryArg = basicArgs.get(0);
        assertEquals("Argument name should be query", "query", queryArg.get("name"));
        assertEquals("Argument description should match", "The search query", queryArg.get("description"));
        assertEquals("Argument should be required", true, queryArg.get("required"));

        // Verify advanced_search prompt arguments
        final Map<String, Object> advancedPrompt = prompts.get(1);
        assertEquals("Prompt name should be advanced_search", "advanced_search", advancedPrompt.get("name"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> advancedArgs = (List<Map<String, Object>>) advancedPrompt.get("arguments");
        assertNotNull("Arguments should not be null", advancedArgs);
        assertEquals("Should have 3 arguments", 3, advancedArgs.size());

        // Check first argument (query)
        final Map<String, Object> advQueryArg = advancedArgs.get(0);
        assertEquals("First argument should be query", "query", advQueryArg.get("name"));
        assertEquals("Query should be required", true, advQueryArg.get("required"));

        // Check second argument (sort)
        final Map<String, Object> sortArg = advancedArgs.get(1);
        assertEquals("Second argument should be sort", "sort", sortArg.get("name"));
        assertEquals("Sort should not be required", false, sortArg.get("required"));

        // Check third argument (num)
        final Map<String, Object> numArg = advancedArgs.get(2);
        assertEquals("Third argument should be num", "num", numArg.get("name"));
        assertEquals("Num should not be required", false, numArg.get("required"));
    }

    @Test
    public void testHandleGetPrompt_BasicSearch() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "test query"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull("GetPrompt result should not be null", result);
        assertTrue("Result should contain messages key", result.containsKey("messages"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertNotNull("Messages list should not be null", messages);
        assertEquals("Should have 1 message", 1, messages.size());

        final Map<String, Object> message = messages.get(0);
        assertEquals("Message role should be user", "user", message.get("role"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) message.get("content");
        assertEquals("Content type should be text", "text", content.get("type"));
        assertTrue("Content text should contain query", content.get("text").toString().contains("test query"));
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("query", "test query", "sort", "score.desc", "num", "10"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull("GetPrompt result should not be null", result);
        assertTrue("Result should contain messages key", result.containsKey("messages"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals("Should have 1 message", 1, messages.size());

        final Map<String, Object> message = messages.get(0);
        assertEquals("Message role should be user", "user", message.get("role"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) message.get("content");
        assertEquals("Content type should be text", "text", content.get("type"));
        final String text = content.get("text").toString();
        assertTrue("Content text should contain query", text.contains("test query"));
        assertTrue("Content text should contain sort", text.contains("score.desc"));
        assertTrue("Content text should contain num", text.contains("10"));
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_MinimalArgs() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("query", "minimal query"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull("GetPrompt result should not be null", result);
        assertTrue("Result should contain messages key", result.containsKey("messages"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals("Should have 1 message", 1, messages.size());

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        final String text = content.get("text").toString();
        assertTrue("Content text should contain query", text.contains("minimal query"));
    }

    @Test(expected = McpApiException.class)
    public void testHandleGetPrompt_MissingName() {
        final Map<String, Object> params = new HashMap<>();
        params.put("arguments", Map.of("query", "test"));
        mcpApiManager.handleGetPrompt(params);
    }

    @Test(expected = McpApiException.class)
    public void testHandleGetPrompt_EmptyName() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "");
        params.put("arguments", Map.of("query", "test"));
        mcpApiManager.handleGetPrompt(params);
    }

    @Test(expected = McpApiException.class)
    public void testHandleGetPrompt_UnknownPrompt() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "unknown_prompt");
        params.put("arguments", Map.of("query", "test"));
        mcpApiManager.handleGetPrompt(params);
    }

    @Test(expected = McpApiException.class)
    public void testHandleGetPrompt_MissingRequiredArgument() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of());
        mcpApiManager.handleGetPrompt(params);
    }

    @Test
    public void testDispatchRpcMethod_PromptsGet() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "dispatch test"));

        final Object result = mcpApiManager.dispatchRpcMethod("prompts/get", params);

        assertNotNull("dispatchRpcMethod should return non-null result for prompts/get", result);
        assertTrue("Result should be a Map", result instanceof Map);

        @SuppressWarnings("unchecked")
        final Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue("Result should contain messages key", resultMap.containsKey("messages"));
    }

    // ==================== Comprehensive prompts/get Tests ====================

    @Test
    public void testHandleGetPrompt_BasicSearch_WithJapaneseQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "インストール方法"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull("Result should not be null", result);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        assertTrue("Content should contain Japanese query", content.get("text").toString().contains("インストール方法"));
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_WithSpecialCharacters() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "test AND (foo OR bar) -exclude \"exact phrase\""));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertNotNull("Result should not be null", result);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        assertTrue("Content should contain special characters", content.get("text").toString().contains("AND"));
        assertTrue("Content should contain special characters", content.get("text").toString().contains("\"exact phrase\""));
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_MessageStructure() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", "test"));

        final Map<String, Object> result = mcpApiManager.handleGetPrompt(params);

        assertTrue("Result should have messages key", result.containsKey("messages"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals("Should have exactly 1 message", 1, messages.size());

        final Map<String, Object> message = messages.get(0);
        assertEquals("Message role should be 'user'", "user", message.get("role"));
        assertTrue("Message should have content", message.containsKey("content"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> content = (Map<String, Object>) message.get("content");
        assertEquals("Content type should be 'text'", "text", content.get("type"));
        assertNotNull("Content text should not be null", content.get("text"));
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_NullArguments() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", null);

        try {
            mcpApiManager.handleGetPrompt(params);
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testHandleGetPrompt_BasicSearch_EmptyQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "basic_search");
        params.put("arguments", Map.of("query", ""));

        try {
            mcpApiManager.handleGetPrompt(params);
            assertTrue("Should have thrown McpApiException for empty query", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention query", e.getMessage().contains("query"));
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

        assertTrue("Content should contain query", text.contains("検索テスト"));
        assertTrue("Content should contain sort", text.contains("last_modified.desc"));
        assertTrue("Content should contain num", text.contains("25"));
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

        assertTrue("Content should contain query", text.contains("test query"));
        assertTrue("Content should contain sort", text.contains("score.desc"));
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

        assertTrue("Content should contain query", text.contains("test query"));
        assertTrue("Content should contain num", text.contains("50"));
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

        assertTrue("Content should contain query", text.contains("test"));
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

        assertTrue("Content should contain num as string", text.contains("100"));
    }

    @Test
    public void testHandleGetPrompt_AdvancedSearch_MissingQuery() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "advanced_search");
        params.put("arguments", Map.of("sort", "score.desc", "num", "10"));

        try {
            mcpApiManager.handleGetPrompt(params);
            assertTrue("Should have thrown McpApiException for missing query", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention query", e.getMessage().contains("query"));
        }
    }

    @Test
    public void testHandleGetPrompt_NullName() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", null);
        params.put("arguments", Map.of("query", "test"));

        try {
            mcpApiManager.handleGetPrompt(params);
            assertTrue("Should have thrown McpApiException for null name", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention name", e.getMessage().contains("name"));
        }
    }

    @Test
    public void testHandleGetPrompt_ErrorCode_UnknownPrompt() {
        final Map<String, Object> params = new HashMap<>();
        params.put("name", "nonexistent_prompt");
        params.put("arguments", Map.of("query", "test"));

        try {
            mcpApiManager.handleGetPrompt(params);
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention unknown prompt", e.getMessage().contains("Unknown prompt"));
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

        assertNotNull("Result should not be null", result);
        assertTrue("Result should be a Map", result instanceof Map);
        @SuppressWarnings("unchecked")
        final Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue("Result should contain messages key", resultMap.containsKey("messages"));
    }

    @Test
    public void testHandleListResources_DetailedFields() {
        final Map<String, Object> result = mcpApiManager.handleListResources();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");

        final Map<String, Object> resource = resources.get(0);
        assertEquals("URI should match", "fess://index/stats", resource.get("uri"));
        assertEquals("Name should match", "Index Statistics", resource.get("name"));
        assertEquals("Description should not be null", "Fess index statistics and configuration information", resource.get("description"));
        assertEquals("MimeType should be application/json", "application/json", resource.get("mimeType"));

        // Verify all expected keys are present
        assertTrue("Resource should have uri", resource.containsKey("uri"));
        assertTrue("Resource should have name", resource.containsKey("name"));
        assertTrue("Resource should have description", resource.containsKey("description"));
        assertTrue("Resource should have mimeType", resource.containsKey("mimeType"));
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
            assertTrue("Should fail due to container not initialized", e.getMessage().contains("container"));
        }
    }

    @Test(expected = McpApiException.class)
    public void testHandleReadResource_MissingUri() {
        final Map<String, Object> params = new HashMap<>();
        mcpApiManager.handleReadResource(params);
    }

    @Test(expected = McpApiException.class)
    public void testHandleReadResource_EmptyUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "");
        mcpApiManager.handleReadResource(params);
    }

    @Test(expected = McpApiException.class)
    public void testHandleReadResource_UnknownResource() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://unknown/resource");
        mcpApiManager.handleReadResource(params);
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
            assertNotNull("dispatchRpcMethod should return non-null result for resources/read", result);
            assertTrue("Result should be a Map", result instanceof Map);
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue("Should fail due to container not initialized", e.getMessage().contains("container"));
        }
    }

    // ==================== Comprehensive resources/read Tests ====================

    @Test
    public void testHandleReadResource_NullUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", null);

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException for null URI", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention uri", e.getMessage().contains("uri"));
        }
    }

    @Test
    public void testHandleReadResource_ErrorCode_MissingUri() {
        final Map<String, Object> params = new HashMap<>();

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention missing parameter", e.getMessage().contains("Missing"));
        }
    }

    @Test
    public void testHandleReadResource_ErrorCode_UnknownResource() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://unknown/path");

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention unknown resource", e.getMessage().contains("Unknown resource"));
        }
    }

    @Test
    public void testHandleReadResource_InvalidScheme() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "http://example.com/resource");

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException for invalid scheme", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testHandleReadResource_WhitespaceUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "   ");

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException for whitespace URI", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testHandleReadResource_PartialUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://index");

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException for partial URI", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testHandleReadResource_CaseSensitiveUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "FESS://INDEX/STATS");

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException for case mismatch URI", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testHandleReadResource_ExtraPathSegment() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://index/stats/extra");

        try {
            mcpApiManager.handleReadResource(params);
            assertTrue("Should have thrown McpApiException for unknown resource", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testDispatchRpcMethod_ResourcesRead_MissingParams() {
        try {
            mcpApiManager.dispatchRpcMethod("resources/read", Map.of());
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testDispatchRpcMethod_ResourcesRead_UnknownUri() {
        final Map<String, Object> params = new HashMap<>();
        params.put("uri", "fess://nonexistent/resource");

        try {
            mcpApiManager.dispatchRpcMethod("resources/read", params);
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testDispatchRpcMethod_ToolsCall() {
        // Test tools/call method with missing name parameter (should trigger error in handleInvoke)
        try {
            mcpApiManager.dispatchRpcMethod("tools/call", Map.of());
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Should be InvalidParams error", ErrorCode.InvalidParams, e.getCode());
            assertTrue("Error message should mention missing name", e.getMessage().contains("name"));
        }
    }

    @Test
    public void testDispatchRpcMethod_AllMethods() {
        // Test all valid methods return non-null results
        final String[] methods = { "initialize", "tools/list", "resources/list", "prompts/list" };

        for (final String method : methods) {
            final Object result = mcpApiManager.dispatchRpcMethod(method, Map.of());
            assertNotNull("Result for method '" + method + "' should not be null", result);
            assertTrue("Result for method '" + method + "' should be a Map", result instanceof Map);
        }
    }

    @Test
    public void testHandleInitialize_CapabilitiesStructure() {
        final Map<String, Object> result = mcpApiManager.handleInitialize();

        @SuppressWarnings("unchecked")
        final Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");

        // Verify capabilities structure
        assertNotNull("tools capability should not be null", capabilities.get("tools"));
        assertNotNull("resources capability should not be null", capabilities.get("resources"));
        assertNotNull("prompts capability should not be null", capabilities.get("prompts"));

        assertTrue("tools should be a Map", capabilities.get("tools") instanceof Map);
        assertTrue("resources should be a Map", capabilities.get("resources") instanceof Map);
        assertTrue("prompts should be a Map", capabilities.get("prompts") instanceof Map);

        // Verify serverInfo structure
        @SuppressWarnings("unchecked")
        final Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");

        assertTrue("serverInfo should have name", serverInfo.containsKey("name"));
        assertTrue("serverInfo should have version", serverInfo.containsKey("version"));
        assertTrue("name should be a String", serverInfo.get("name") instanceof String);
        assertTrue("version should be a String", serverInfo.get("version") instanceof String);
    }

    @Test(expected = NullPointerException.class)
    public void testDispatchRpcMethod_NullMethod() {
        mcpApiManager.dispatchRpcMethod(null, Map.of());
    }

    @Test
    public void testErrorCodeInException() {
        // Test that different error codes are properly returned in exceptions
        try {
            mcpApiManager.dispatchRpcMethod("unknown_method", Map.of());
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Error code should be MethodNotFound", ErrorCode.MethodNotFound, e.getCode());
        }

        try {
            mcpApiManager.handleInvoke(Map.of());
            assertTrue("Should have thrown McpApiException", false);
        } catch (final McpApiException e) {
            assertEquals("Error code should be InvalidParams", ErrorCode.InvalidParams, e.getCode());
        }
    }

    @Test
    public void testConstructor() {
        final McpApiManager manager = new McpApiManager();
        assertNotNull("McpApiManager should be created", manager);
    }

    @Test
    public void testCreateDocumentContent() {
        final Map<String, Object> doc =
                Map.of("title", "Test Document", "url", "https://example.com/test", "content", "This is test content.", "score", 10.5);

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);

        assertNotNull("Result should not be null", result);
        assertEquals("Type should be text", "text", result.get("type"));

        final String text = (String) result.get("text");
        assertNotNull("Text should not be null", text);
        assertTrue("Text should contain Title", text.contains("**Title**: Test Document"));
        assertTrue("Text should contain URL", text.contains("**URL**: https://example.com/test"));
        assertTrue("Text should contain Score", text.contains("**Score**: 10.5"));
        assertTrue("Text should contain content", text.contains("This is test content."));
    }

    @Test
    public void testCreateDocumentContent_WithoutScore() {
        final Map<String, Object> doc =
                Map.of("title", "Test Document", "url", "https://example.com/test", "content", "This is test content.");

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 2);

        final String text = (String) result.get("text");
        assertTrue("Text should contain Title", text.contains("**Title**: Test Document"));
        assertTrue("Text should not contain Score", !text.contains("**Score**:"));
    }

    @Test
    public void testTruncateContent() {
        // Test with null
        assertEquals("Null should return null", null, mcpApiManager.truncateContent(null, 100));

        // Test with short content
        final String shortContent = "Short";
        assertEquals("Short content should not be truncated", shortContent, mcpApiManager.truncateContent(shortContent, 100));

        // Test with exact length content
        final String exactContent = "12345";
        assertEquals("Exact length content should not be truncated", exactContent, mcpApiManager.truncateContent(exactContent, 5));

        // Test with long content
        final String longContent = "This is a long content that should be truncated";
        final String truncated = mcpApiManager.truncateContent(longContent, 10);
        assertEquals("Truncated content should be 10 chars + ...", "This is a ...", truncated);
    }

    @Test
    public void testGetContentMaxLength() {
        // Default value should be 10000
        assertEquals("Default max length should be 10000", 10000, mcpApiManager.getContentMaxLength());
    }

    @Test
    public void testGetContentMaxLength_WithSystemProperty() {
        final String originalValue = System.getProperty("mcp.content.max.length");
        try {
            System.setProperty("mcp.content.max.length", "5000");
            assertEquals("Max length should be 5000", 5000, mcpApiManager.getContentMaxLength());
        } finally {
            if (originalValue != null) {
                System.setProperty("mcp.content.max.length", originalValue);
            } else {
                System.clearProperty("mcp.content.max.length");
            }
        }
    }

    @Test
    public void testSearchToolDescription_ContainsQuerySyntaxInfo() {
        final Map<String, Object> result = mcpApiManager.handleListTools();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        final Map<String, Object> searchTool = tools.get(0);
        final String description = (String) searchTool.get("description");

        assertTrue("Description should mention Lucene", description.contains("Lucene"));
        assertTrue("Description should mention AND", description.contains("AND"));
        assertTrue("Description should mention OR", description.contains("OR"));
        assertTrue("Description should mention phrase search", description.contains("phrase"));
        assertTrue("Description should mention exclusion", description.contains("exclusion") || description.contains("-"));
    }

    @Test
    public void testProcessDocumentItems_Null() {
        final List<Map<String, Object>> result = mcpApiManager.processDocumentItems(null);
        assertNotNull("Result should not be null", result);
        assertTrue("Result should be empty", result.isEmpty());
    }

    @Test
    public void testProcessDocumentItems_EmptyList() {
        final List<Map<String, Object>> result = mcpApiManager.processDocumentItems(List.of());
        assertNotNull("Result should not be null", result);
        assertTrue("Result should be empty", result.isEmpty());
    }

    @Test
    public void testProcessDocumentItems_WithDocuments() {
        final List<Map<String, Object>> docs =
                List.of(Map.of("title", "Doc1", "url", "http://example.com/1"), Map.of("title", "Doc2", "url", "http://example.com/2"));

        final List<Map<String, Object>> result = mcpApiManager.processDocumentItems(docs);

        assertEquals("Should have 2 documents", 2, result.size());
        assertEquals("First doc title", "Doc1", result.get(0).get("title"));
        assertEquals("Second doc title", "Doc2", result.get(1).get("title"));
    }

    @Test
    public void testProcessValue_Null() {
        assertEquals("Null should return null", null, mcpApiManager.processValue(null));
    }

    @Test
    public void testProcessValue_String() {
        assertEquals("String should be unchanged", "test", mcpApiManager.processValue("test"));
    }

    @Test
    public void testProcessValue_Number() {
        assertEquals("Number should be unchanged", 123, mcpApiManager.processValue(123));
        assertEquals("Double should be unchanged", 1.5, mcpApiManager.processValue(1.5));
    }

    @Test
    public void testProcessValue_List() {
        final List<Object> input = List.of("a", "b", 1);
        @SuppressWarnings("unchecked")
        final List<Object> result = (List<Object>) mcpApiManager.processValue(input);

        assertEquals("List size should be 3", 3, result.size());
        assertEquals("First element", "a", result.get(0));
        assertEquals("Second element", "b", result.get(1));
        assertEquals("Third element", 1, result.get(2));
    }

    @Test
    public void testProcessValue_Map() {
        final Map<String, Object> input = Map.of("key1", "value1", "key2", 123);
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.processValue(input);

        assertEquals("Map size should be 2", 2, result.size());
        assertEquals("key1 value", "value1", result.get("key1"));
        assertEquals("key2 value", 123, result.get("key2"));
    }

    @Test
    public void testProcessValue_Array() {
        final Object[] input = new Object[] { "a", "b", 1 };
        @SuppressWarnings("unchecked")
        final List<Object> result = (List<Object>) mcpApiManager.processValue(input);

        assertEquals("Array should be converted to List with size 3", 3, result.size());
        assertEquals("First element", "a", result.get(0));
    }

    @Test
    public void testCreateDocumentContent_EmptyDocument() {
        final Map<String, Object> doc = Map.of();

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);

        assertNotNull("Result should not be null", result);
        assertEquals("Type should be text", "text", result.get("type"));

        final String text = (String) result.get("text");
        assertTrue("Text should contain Title label", text.contains("**Title**:"));
        assertTrue("Text should contain URL label", text.contains("**URL**:"));
    }

    @Test
    public void testCreateDocumentContent_WithContentTruncation() {
        final String originalValue = System.getProperty("mcp.content.max.length");
        try {
            System.setProperty("mcp.content.max.length", "20");

            final String longContent = "This is a very long content that should be truncated";
            final Map<String, Object> doc = Map.of("title", "Test", "url", "http://test.com", "content", longContent);

            final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);
            final String text = (String) result.get("text");

            assertTrue("Content should be truncated with ...", text.contains("..."));
            assertTrue("Full content should not be present", !text.contains("should be truncated"));
        } finally {
            if (originalValue != null) {
                System.setProperty("mcp.content.max.length", originalValue);
            } else {
                System.clearProperty("mcp.content.max.length");
            }
        }
    }

    @Test
    public void testTruncateContent_EmptyString() {
        assertEquals("Empty string should return empty", "", mcpApiManager.truncateContent("", 100));
    }

    @Test
    public void testTruncateContent_ZeroMaxLength() {
        final String content = "test";
        final String result = mcpApiManager.truncateContent(content, 0);
        assertEquals("Zero max length should return ...", "...", result);
    }
}
