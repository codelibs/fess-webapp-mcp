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
        mcpApiManager = new TestMcpApiManager();
    }

    /**
     * Test subclass that overrides methods requiring DI container.
     */
    private static class TestMcpApiManager extends McpApiManager {
        private int contentMaxLength = 10000;

        public void setContentMaxLength(final int contentMaxLength) {
            this.contentMaxLength = contentMaxLength;
        }

        @Override
        protected int getContentMaxLength() {
            return contentMaxLength;
        }
    }

    @Test
    public void testHandleInitialize() {
        final Map<String, Object> result = mcpApiManager.handleInitialize();

        assertNotNull(result, "Initialize result should not be null");
        assertEquals("2024-11-05", result.get("protocolVersion"), "Protocol version should be 2024-11-05");

        @SuppressWarnings("unchecked")
        final Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
        assertNotNull(capabilities, "Capabilities should not be null");
        assertTrue(capabilities.containsKey("tools"), "Capabilities should include tools");
        assertTrue(capabilities.containsKey("resources"), "Capabilities should include resources");
        assertTrue(capabilities.containsKey("prompts"), "Capabilities should include prompts");

        @SuppressWarnings("unchecked")
        final Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertNotNull(serverInfo, "ServerInfo should not be null");
        assertEquals("fess-mcp-server", serverInfo.get("name"), "Server name should be fess-mcp-server");
        assertEquals("1.0.0", serverInfo.get("version"), "Server version should be 1.0.0");
    }

    @Test
    public void testHandleInitialize_HasInstructions() {
        final Map<String, Object> result = mcpApiManager.handleInitialize();
        assertNotNull(result.get("instructions"), "Should have instructions");
        assertTrue(result.get("instructions") instanceof String && !((String) result.get("instructions")).isEmpty(),
                "Instructions should be a non-empty string");
    }

    @Test
    public void testHandleInitialize_DoesNotAdvertiseLoggingCapability() {
        final Map<String, Object> result = mcpApiManager.handleInitialize();
        @SuppressWarnings("unchecked")
        final Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
        assertFalse(capabilities.containsKey("logging"),
                "Should not advertise logging capability (HTTP request/response server cannot emit notifications/message)");
    }

    @Test
    public void testHandleInitialize_HasCompletionsCapability() {
        final Map<String, Object> result = mcpApiManager.handleInitialize();
        @SuppressWarnings("unchecked")
        final Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
        assertNotNull(capabilities.get("completions"), "Should have completions capability");
    }

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
    public void testDispatchRpcMethod_Initialize() {
        final Object result = mcpApiManager.dispatchRpcMethod("initialize", Map.of());
        assertNotNull(result, "Dispatch result should not be null");
        assertTrue(result instanceof Map, "Result should be a Map");
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
            assertEquals(ErrorCode.ResourceNotFound, e.getCode(), "Should be ResourceNotFound error");
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
            assertEquals(ErrorCode.ResourceNotFound, e.getCode(), "Should be ResourceNotFound error");
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
            assertEquals(ErrorCode.ResourceNotFound, e.getCode(), "Should be ResourceNotFound error");
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
            assertEquals(ErrorCode.ResourceNotFound, e.getCode(), "Should be ResourceNotFound error");
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
            assertEquals(ErrorCode.ResourceNotFound, e.getCode(), "Should be ResourceNotFound error");
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
            assertEquals(ErrorCode.ResourceNotFound, e.getCode(), "Should be ResourceNotFound error");
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
        // Test all valid methods return non-null results
        final String[] methods = { "initialize", "tools/list", "resources/list", "prompts/list" };

        for (final String method : methods) {
            final Object result = mcpApiManager.dispatchRpcMethod(method, Map.of());
            assertNotNull(result, "Result for method '" + method + "' should not be null");
            assertTrue(result instanceof Map, "Result for method '" + method + "' should be a Map");
        }
    }

    @Test
    public void testHandleInitialize_CapabilitiesStructure() {
        final Map<String, Object> result = mcpApiManager.handleInitialize();

        @SuppressWarnings("unchecked")
        final Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");

        // Verify capabilities structure
        assertNotNull(capabilities.get("tools"), "tools capability should not be null");
        assertNotNull(capabilities.get("resources"), "resources capability should not be null");
        assertNotNull(capabilities.get("prompts"), "prompts capability should not be null");

        assertTrue(capabilities.get("tools") instanceof Map, "tools should be a Map");
        assertTrue(capabilities.get("resources") instanceof Map, "resources should be a Map");
        assertTrue(capabilities.get("prompts") instanceof Map, "prompts should be a Map");

        // Verify serverInfo structure
        @SuppressWarnings("unchecked")
        final Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");

        assertTrue(serverInfo.containsKey("name"), "serverInfo should have name");
        assertTrue(serverInfo.containsKey("version"), "serverInfo should have version");
        assertTrue(serverInfo.get("name") instanceof String, "name should be a String");
        assertTrue(serverInfo.get("version") instanceof String, "version should be a String");
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
    public void testCreateDocumentContent() {
        final Map<String, Object> doc =
                Map.of("title", "Test Document", "url", "https://example.com/test", "content", "This is test content.", "score", 10.5);

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);

        assertNotNull(result, "Result should not be null");
        assertEquals("text", result.get("type"), "Type should be text");

        final String text = (String) result.get("text");
        assertNotNull(text, "Text should not be null");
        assertTrue(text.contains("**Title**: Test Document"), "Text should contain Title");
        assertTrue(text.contains("**URL**: https://example.com/test"), "Text should contain URL");
        assertTrue(text.contains("**Score**: 10.5"), "Text should contain Score");
        assertTrue(text.contains("This is test content."), "Text should contain content");
    }

    @Test
    public void testCreateDocumentContent_WithoutScore() {
        final Map<String, Object> doc =
                Map.of("title", "Test Document", "url", "https://example.com/test", "content", "This is test content.");

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 2);

        final String text = (String) result.get("text");
        assertTrue(text.contains("**Title**: Test Document"), "Text should contain Title");
        assertTrue(!text.contains("**Score**:"), "Text should not contain Score");
    }

    @Test
    public void testTruncateContent() {
        // Test with null
        assertEquals(null, mcpApiManager.truncateContent(null, 100), "Null should return null");

        // Test with short content
        final String shortContent = "Short";
        assertEquals(shortContent, mcpApiManager.truncateContent(shortContent, 100), "Short content should not be truncated");

        // Test with exact length content
        final String exactContent = "12345";
        assertEquals(exactContent, mcpApiManager.truncateContent(exactContent, 5), "Exact length content should not be truncated");

        // Test with long content
        final String longContent = "This is a long content that should be truncated";
        final String truncated = mcpApiManager.truncateContent(longContent, 10);
        assertEquals("This is a ...", truncated, "Truncated content should be 10 chars + ...");
    }

    @Test
    public void testStripHighlightTags() {
        // Test with em tags
        assertEquals("highlighted text", mcpApiManager.stripHighlightTags("<em>highlighted</em> text"));

        // Test with strong tags
        assertEquals("highlighted text", mcpApiManager.stripHighlightTags("<strong>highlighted</strong> text"));

        // Test with mixed tags
        assertEquals("hello world test", mcpApiManager.stripHighlightTags("<em>hello</em> <strong>world</strong> test"));

        // Test with null
        assertEquals("", mcpApiManager.stripHighlightTags(null));

        // Test with empty string
        assertEquals("", mcpApiManager.stripHighlightTags(""));

        // Test with no tags
        assertEquals("plain text", mcpApiManager.stripHighlightTags("plain text"));

        // Test with nested content
        assertEquals("multiple highlights here",
                mcpApiManager.stripHighlightTags("<em>multiple</em> <em>highlights</em> <strong>here</strong>"));
    }

    @Test
    public void testCreateDocumentContentWithContentDescription() {
        // Test with content_description (highlighted content)
        final Map<String, Object> doc = new HashMap<>();
        doc.put("title", "Test Document");
        doc.put("url", "https://example.com/test");
        doc.put("content", "This is raw content that should not appear.");
        doc.put("content_description", "<em>highlighted</em> search result content");
        doc.put("score", 10.5);

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);

        assertNotNull(result, "Result should not be null");
        assertEquals("text", result.get("type"), "Type should be text");

        final String text = (String) result.get("text");
        assertNotNull(text, "Text should not be null");
        assertTrue(text.contains("**Title**: Test Document"), "Text should contain Title");
        assertTrue(text.contains("**URL**: https://example.com/test"), "Text should contain URL");
        assertTrue(text.contains("**Score**: 10.5"), "Text should contain Score");
        // Verify content_description is used and tags are stripped
        assertTrue(text.contains("highlighted search result content"), "Text should contain stripped highlighted content");
        assertFalse(text.contains("<em>"), "Text should not contain HTML tags");
        assertFalse(text.contains("raw content that should not appear"), "Text should not contain raw content");
    }

    @Test
    public void testCreateDocumentContentFallbackToContent() {
        // Test fallback when content_description is empty
        final Map<String, Object> doc = new HashMap<>();
        doc.put("title", "Test Document");
        doc.put("url", "https://example.com/test");
        doc.put("content", "This is the raw content used as fallback.");
        doc.put("content_description", "");
        doc.put("score", 5.0);

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);

        final String text = (String) result.get("text");
        assertTrue(text.contains("This is the raw content used as fallback."), "Text should contain raw content as fallback");
    }

    @Test
    public void testGetContentMaxLength() {
        // TestMcpApiManager returns default value 10000
        assertEquals(10000, mcpApiManager.getContentMaxLength(), "Default max length should be 10000");
    }

    @Test
    public void testGetContentMaxLength_WithSystemProperty() {
        // Note: This test verifies TestMcpApiManager behavior.
        // Actual FessConfig integration requires DI container and is tested in integration tests.
        // TestMcpApiManager always returns 10000 regardless of system property.
        assertEquals(10000, mcpApiManager.getContentMaxLength(), "TestMcpApiManager should return 10000");
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

    @Test
    public void testProcessDocumentItems_Null() {
        final List<Map<String, Object>> result = mcpApiManager.processDocumentItems(null);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty");
    }

    @Test
    public void testProcessDocumentItems_EmptyList() {
        final List<Map<String, Object>> result = mcpApiManager.processDocumentItems(List.of());
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty");
    }

    @Test
    public void testProcessDocumentItems_WithDocuments() {
        final List<Map<String, Object>> docs =
                List.of(Map.of("title", "Doc1", "url", "http://example.com/1"), Map.of("title", "Doc2", "url", "http://example.com/2"));

        final List<Map<String, Object>> result = mcpApiManager.processDocumentItems(docs);

        assertEquals(2, result.size(), "Should have 2 documents");
        assertEquals("Doc1", result.get(0).get("title"), "First doc title");
        assertEquals("Doc2", result.get(1).get("title"), "Second doc title");
    }

    @Test
    public void testProcessValue_Null() {
        assertEquals(null, mcpApiManager.processValue(null), "Null should return null");
    }

    @Test
    public void testProcessValue_String() {
        assertEquals("test", mcpApiManager.processValue("test"), "String should be unchanged");
    }

    @Test
    public void testProcessValue_Number() {
        assertEquals(123, mcpApiManager.processValue(123), "Number should be unchanged");
        assertEquals(1.5, mcpApiManager.processValue(1.5), "Double should be unchanged");
    }

    @Test
    public void testProcessValue_List() {
        final List<Object> input = List.of("a", "b", 1);
        @SuppressWarnings("unchecked")
        final List<Object> result = (List<Object>) mcpApiManager.processValue(input);

        assertEquals(3, result.size(), "List size should be 3");
        assertEquals("a", result.get(0), "First element");
        assertEquals("b", result.get(1), "Second element");
        assertEquals(1, result.get(2), "Third element");
    }

    @Test
    public void testProcessValue_Map() {
        final Map<String, Object> input = Map.of("key1", "value1", "key2", 123);
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) mcpApiManager.processValue(input);

        assertEquals(2, result.size(), "Map size should be 2");
        assertEquals("value1", result.get("key1"), "key1 value");
        assertEquals(123, result.get("key2"), "key2 value");
    }

    @Test
    public void testProcessValue_Array() {
        final Object[] input = new Object[] { "a", "b", 1 };
        @SuppressWarnings("unchecked")
        final List<Object> result = (List<Object>) mcpApiManager.processValue(input);

        assertEquals(3, result.size(), "Array should be converted to List with size 3");
        assertEquals("a", result.get(0), "First element");
    }

    @Test
    public void testCreateDocumentContent_EmptyDocument() {
        final Map<String, Object> doc = Map.of();

        final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);

        assertNotNull(result, "Result should not be null");
        assertEquals("text", result.get("type"), "Type should be text");

        final String text = (String) result.get("text");
        assertTrue(text.contains("**Title**:"), "Text should contain Title label");
        assertTrue(text.contains("**URL**:"), "Text should contain URL label");
    }

    @Test
    public void testCreateDocumentContent_WithContentTruncation() {
        // Set a small max length to test truncation
        ((TestMcpApiManager) mcpApiManager).setContentMaxLength(20);

        try {
            // content_description is empty, so content will be used with truncation
            final String longContent = "This is a very long content that should be truncated";
            final Map<String, Object> doc = new HashMap<>();
            doc.put("title", "Test");
            doc.put("url", "http://test.com");
            doc.put("content", longContent);
            doc.put("content_description", ""); // Empty to trigger fallback to content

            final Map<String, Object> result = mcpApiManager.createDocumentContent(doc, 1);
            final String text = (String) result.get("text");

            assertTrue(text.contains("..."), "Content should be truncated with ...");
            assertFalse(text.contains("should be truncated"), "Full content should not be present");
        } finally {
            // Reset to default
            ((TestMcpApiManager) mcpApiManager).setContentMaxLength(10000);
        }
    }

    @Test
    public void testTruncateContent_EmptyString() {
        assertEquals("", mcpApiManager.truncateContent("", 100), "Empty string should return empty");
    }

    @Test
    public void testTruncateContent_ZeroMaxLength() {
        final String content = "test";
        final String result = mcpApiManager.truncateContent(content, 0);
        assertEquals("...", result, "Zero max length should return ...");
    }

    @Test
    public void testHandleListTools_NumParameterDefaultValue() {
        final Map<String, Object> result = mcpApiManager.handleListTools();

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        final Map<String, Object> searchTool = tools.get(0);

        @SuppressWarnings("unchecked")
        final Map<String, Object> searchSchema = (Map<String, Object>) searchTool.get("inputSchema");

        @SuppressWarnings("unchecked")
        final Map<String, Object> searchProperties = (Map<String, Object>) searchSchema.get("properties");

        @SuppressWarnings("unchecked")
        final Map<String, Object> numProperty = (Map<String, Object>) searchProperties.get("num");
        assertNotNull(numProperty, "num property should exist");

        final String numDescription = (String) numProperty.get("description");
        assertNotNull(numDescription, "num description should not be null");
        assertEquals("number of results", numDescription);

        final Object defaultValue = numProperty.get("default");
        assertNotNull(defaultValue, "num default should exist in schema");
        assertEquals(3, defaultValue, "num default should be 3");
    }

    // ==================== Index Stats Tests ====================

    @Test
    public void testCollectIndexStats_RequiresDIContainer() {
        // collectIndexStats requires ComponentUtil which needs DI container
        try {
            mcpApiManager.collectIndexStats();
            // If container is initialized, we would get a valid result
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testInvokeGetIndexStats_RequiresDIContainer() {
        // invokeGetIndexStats requires ComponentUtil which needs DI container
        try {
            mcpApiManager.invokeGetIndexStats();
            // If container is initialized, we would get a valid result
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

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
            // ResourceNotFound is also acceptable if container is available but doc doesn't exist
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
        final List<Map<String, Object>> requests = List.of(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()),
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
        requests.add(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()));

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

    // ==================== initialize protocol negotiation tests ====================

    @Test
    public void testHandleInitialize_NegotiateSupportedVersion() {
        final Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        final Map<String, Object> result = mcpApiManager.handleInitialize(params);
        assertEquals("2024-11-05", result.get("protocolVersion"), "Should echo back supported version");
    }

    @Test
    public void testHandleInitialize_FallbackUnsupportedVersion() {
        final Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2099-01-01");
        final Map<String, Object> result = mcpApiManager.handleInitialize(params);
        assertEquals("2024-11-05", result.get("protocolVersion"), "Should fall back to latest supported version");
    }

    @Test
    public void testHandleInitialize_NoParamsUsesLatest() {
        final Map<String, Object> result = mcpApiManager.handleInitialize(Map.of());
        assertEquals("2024-11-05", result.get("protocolVersion"), "Should use latest when no protocolVersion provided");
    }

    @Test
    public void testHandleInitialize_AcceptsClientInfo() {
        final Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("clientInfo", Map.of("name", "test-client", "version", "0.1"));
        final Map<String, Object> result = mcpApiManager.handleInitialize(params);
        assertNotNull(result, "Should still produce a result when clientInfo is present");
        assertEquals("2024-11-05", result.get("protocolVersion"));
    }

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

    // ==================== initialize protocolVersion type robustness ====================

    @Test
    public void testHandleInitialize_NonStringProtocolVersionNumberFallsBackToLatest() {
        // Non-String protocolVersion (e.g., numeric) must be treated as unsupported
        // and the server should fall back to the latest supported version.
        final Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", Integer.valueOf(20241105));
        final Map<String, Object> result = mcpApiManager.handleInitialize(params);
        assertEquals("2024-11-05", result.get("protocolVersion"), "Non-String protocolVersion should fall back to latest");
    }

    @Test
    public void testHandleInitialize_NonStringProtocolVersionMapFallsBackToLatest() {
        final Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", Map.of("major", 2024));
        final Map<String, Object> result = mcpApiManager.handleInitialize(params);
        assertEquals("2024-11-05", result.get("protocolVersion"), "Map-typed protocolVersion should fall back to latest");
    }

    @Test
    public void testHandleInitialize_EmptyStringProtocolVersionFallsBackToLatest() {
        final Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "");
        final Map<String, Object> result = mcpApiManager.handleInitialize(params);
        assertEquals("2024-11-05", result.get("protocolVersion"), "Empty protocolVersion should fall back to latest");
    }

    @Test
    public void testHandleInitialize_ClientInfoNotEchoed() {
        // The server must NOT retain or echo clientInfo in the initialize response.
        final Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("clientInfo", Map.of("name", "test-client", "version", "0.1"));
        final Map<String, Object> result = mcpApiManager.handleInitialize(params);
        assertFalse(result.containsKey("clientInfo"), "Response must not include clientInfo key");
        // serverInfo must be the fixed server identity, not the client's
        @SuppressWarnings("unchecked")
        final Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertEquals("fess-mcp-server", serverInfo.get("name"), "serverInfo.name must be server identity");
    }

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

    // ==================== invokeSuggest num resolution / cap ====================

    @Test
    public void testResolveSuggestSize_NullUsesDefault() {
        assertEquals(10, mcpApiManager.resolveSuggestSize(null, 100), "null num should default to 10");
    }

    @Test
    public void testResolveSuggestSize_Zero_UsesDefault() {
        assertEquals(10, mcpApiManager.resolveSuggestSize(Integer.valueOf(0), 100), "num=0 should fall back to default 10");
    }

    @Test
    public void testResolveSuggestSize_Negative_UsesDefault() {
        assertEquals(10, mcpApiManager.resolveSuggestSize(Integer.valueOf(-5), 100), "negative num should fall back to default 10");
    }

    @Test
    public void testResolveSuggestSize_OverMax_CappedAtMax() {
        assertEquals(100, mcpApiManager.resolveSuggestSize(Integer.valueOf(500), 100), "num exceeding max must be capped");
    }

    @Test
    public void testResolveSuggestSize_AtMax_Preserved() {
        assertEquals(100, mcpApiManager.resolveSuggestSize(Integer.valueOf(100), 100), "num equal to max must be preserved");
    }

    @Test
    public void testResolveSuggestSize_WithinRange_Preserved() {
        assertEquals(25, mcpApiManager.resolveSuggestSize(Integer.valueOf(25), 100), "num within range must be preserved");
    }

    @Test
    public void testResolveSuggestSize_StringNumericInput_Parsed() {
        assertEquals(7, mcpApiManager.resolveSuggestSize("7", 100), "numeric String must be parsed");
    }

    @Test
    public void testResolveSuggestSize_StringNonNumericInput_UsesDefault() {
        assertEquals(10, mcpApiManager.resolveSuggestSize("abc", 100), "non-numeric String must default to 10");
    }

    @Test
    public void testResolveSuggestSize_StringOverMax_CappedAtMax() {
        assertEquals(100, mcpApiManager.resolveSuggestSize("99999", 100), "numeric String exceeding max must be capped");
    }

    @Test
    public void testResolveSuggestSize_LongNumber_TruncatedToInt() {
        assertEquals(42, mcpApiManager.resolveSuggestSize(Long.valueOf(42L), 100), "Long within range must be preserved via intValue");
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
