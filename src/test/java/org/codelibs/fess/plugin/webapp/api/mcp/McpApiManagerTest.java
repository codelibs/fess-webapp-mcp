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
        assertEquals("Search tool description", "Search documents via Fess", searchTool.get("description"));
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
}
