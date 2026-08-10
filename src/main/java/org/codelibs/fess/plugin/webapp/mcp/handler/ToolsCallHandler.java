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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.GetDocumentTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.IndexStatsTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.SearchTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.SuggestTool;

/**
 * The {@code tools/call} handler.
 *
 * <p>
 * Not a {@code CacheableResult}: {@code CallToolResult} is explicitly excluded from the schema's
 * cacheable-result union, so this result never carries {@code ttlMs} or {@code cacheScope}.
 * </p>
 */
public class ToolsCallHandler implements McpMethodHandler {

    private static final Logger logger = LogManager.getLogger(ToolsCallHandler.class);

    /** The tools this handler can invoke. */
    private final List<McpTool> tools;

    /**
     * Creates a {@code tools/call} handler backed by this server's standard tool set.
     */
    public ToolsCallHandler() {
        this(List.of(new SearchTool(), new IndexStatsTool(), new SuggestTool(), new GetDocumentTool()));
    }

    /**
     * Creates a {@code tools/call} handler backed by an explicit tool set.
     *
     * @param tools the tools this handler can invoke
     */
    public ToolsCallHandler(final List<McpTool> tools) {
        this.tools = tools;
    }

    @Override
    public String getMethod() {
        return "tools/call";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(final McpCallContext context) {
        final Map<String, Object> params = context.getParams();

        final Object nameObj = params.get("name");
        final String name = nameObj instanceof String ? (String) nameObj : null;
        if (name == null || name.isEmpty()) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required parameter: name");
        }

        final Object argumentsObj = params.get("arguments");
        if (!(argumentsObj instanceof Map)) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required parameter: arguments");
        }
        final Map<String, Object> arguments = (Map<String, Object>) argumentsObj;

        final McpTool tool = findTool(name);
        if (tool == null) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Unknown tool: " + name);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Invoking tool: name={}, arguments={}", name, arguments);
        }

        try {
            // Defensive copy: McpResponseWriter#writeResult mutates the result via putIfAbsent,
            // but several McpTool implementations still return an immutable Map.of(...).
            return new LinkedHashMap<>(tool.call(arguments, context));
        } catch (final McpApiException e) {
            // McpTool#call still throws the pre-2026-07-28 exception type (Task 7 scope). Bridge
            // it into the handler-level McpError contract -- same ErrorCode, HTTP 200 because
            // this is an application-level failure -- without changing the tool itself.
            throw new McpError(HttpServletResponse.SC_OK, e.getCode(), e.getMessage());
        } catch (final Exception e) {
            logger.warn("[MCP] Tool '{}' execution failed: {}", name, e.getMessage(), e);
            final String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", "Error: " + message)));
            result.put("isError", true);
            return result;
        }
    }

    /**
     * Finds a registered tool by name.
     *
     * @param name the tool name
     * @return the matching tool, or {@code null} when no registered tool has that name
     */
    protected McpTool findTool(final String name) {
        for (final McpTool tool : tools) {
            if (tool.getName().equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
