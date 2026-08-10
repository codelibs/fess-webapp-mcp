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

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;

/**
 * The {@code prompts/get} handler.
 *
 * <p>
 * Not a {@code CacheableResult}: {@code GetPromptResult} is not in the schema's cacheable-result
 * union, so this result never carries {@code ttlMs} or {@code cacheScope}.
 * </p>
 */
public class PromptsGetHandler implements McpMethodHandler {

    /**
     * Creates a {@code prompts/get} handler.
     */
    public PromptsGetHandler() {
        // nothing to initialize
    }

    @Override
    public String getMethod() {
        return "prompts/get";
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
        final Map<String, Object> arguments = argumentsObj instanceof Map ? (Map<String, Object>) argumentsObj : Map.of();

        return switch (name) {
        case "basic_search" -> buildBasicSearchPrompt(arguments);
        case "advanced_search" -> buildAdvancedSearchPrompt(arguments);
        default -> throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Unknown prompt: " + name);
        };
    }

    /**
     * Builds the {@code basic_search} prompt messages.
     *
     * @param arguments the prompt arguments
     * @return a map with {@code messages}
     * @throws McpError with HTTP 200 and {@link ErrorCode#InvalidParams} when {@code query} is
     *             missing or empty
     */
    protected Map<String, Object> buildBasicSearchPrompt(final Map<String, Object> arguments) {
        final Object queryObj = arguments.get("query");
        final String query = queryObj instanceof String ? (String) queryObj : null;
        if (query == null || query.isEmpty()) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required argument: query");
        }

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", "Please search for: " + query);

        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", List.of(message));
        return result;
    }

    /**
     * Builds the {@code advanced_search} prompt messages.
     *
     * @param arguments the prompt arguments
     * @return a map with {@code messages}
     * @throws McpError with HTTP 200 and {@link ErrorCode#InvalidParams} when {@code query} is
     *             missing or empty
     */
    protected Map<String, Object> buildAdvancedSearchPrompt(final Map<String, Object> arguments) {
        final Object queryObj = arguments.get("query");
        final String query = queryObj instanceof String ? (String) queryObj : null;
        if (query == null || query.isEmpty()) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required argument: query");
        }

        final StringBuilder text = new StringBuilder();
        text.append("Please perform an advanced search with the following parameters:\n");
        text.append("Query: ").append(query);

        final Object sort = arguments.get("sort");
        if (sort != null && !sort.toString().isEmpty()) {
            text.append("\nSort: ").append(sort);
        }

        final Object num = arguments.get("num");
        if (num != null && !num.toString().isEmpty()) {
            text.append("\nNumber of results: ").append(num);
        }

        final Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text.toString());

        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", List.of(message));
        return result;
    }
}
