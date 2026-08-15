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
import java.util.stream.Collectors;

import org.codelibs.fess.plugin.webapp.mcp.auth.PermissionGate;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;

/**
 * The {@code tools/list} handler.
 */
public class ToolsListHandler extends AbstractCacheableHandler {

    /** The hot-reloadable system property key holding this result's TTL. */
    protected static final String TTL_CONFIG_KEY = "mcp.cache.list.ttl.ms";

    /** Default TTL when {@value #TTL_CONFIG_KEY} is unset or unparseable. */
    protected static final long DEFAULT_TTL_MS = 3_600_000L;

    /** The tools this server exposes, in {@code tools/list} order. */
    private final List<McpTool> tools;

    /**
     * Creates a {@code tools/list} handler backed by this server's standard tool set.
     */
    public ToolsListHandler() {
        this(McpTool.defaultTools());
    }

    /**
     * Creates a {@code tools/list} handler backed by an explicit tool set.
     *
     * @param tools the tools to advertise, in the order they should be listed
     */
    public ToolsListHandler(final List<McpTool> tools) {
        super(TTL_CONFIG_KEY, DEFAULT_TTL_MS);
        this.tools = tools;
    }

    @Override
    public String getMethod() {
        return "tools/list";
    }

    @Override
    public Map<String, Object> handle(final McpCallContext context) {
        rejectCursor(context);
        final List<Map<String, Object>> descriptors = tools.stream()
                .filter(tool -> PermissionGate.isAllowed(tool.getRequiredPermissions(), context.getPrincipal()))
                .map(this::describeTool)
                .collect(Collectors.toList());

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", descriptors);
        putCacheHints(result, context);
        return result;
    }

    /**
     * Builds the {@code tools/list} descriptor for one tool.
     * <p>
     * {@code outputSchema} is included: every {@link McpTool} implementation now populates
     * {@code structuredContent} in its {@code call} result conforming to
     * {@link McpTool#getOutputSchema()}, so advertising the schema here keeps the promise -- a
     * declared schema the client never sees would be useless.
     * </p>
     *
     * @param tool the tool to describe
     * @return a map with {@code name}, {@code description}, {@code inputSchema},
     *         {@code outputSchema}, and {@code annotations}
     */
    protected Map<String, Object> describeTool(final McpTool tool) {
        final Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", tool.getName());
        descriptor.put("description", tool.getDescription());
        descriptor.put("inputSchema", tool.getInputSchema());
        descriptor.put("outputSchema", tool.getOutputSchema());
        descriptor.put("annotations", tool.getAnnotations());
        return descriptor;
    }

    @Override
    protected String getCacheScope(final McpCallContext context) {
        // This result now varies by the caller's authorization once get_index_stats is gated: a
        // public scope could be shared between callers with different permissions even on an
        // authenticated endpoint (design doc 6.2), so this drops to private whenever
        // mcp.auth.mode is not the unauthenticated default.
        return AUTH_MODE_NONE.equals(getAuthMode()) ? "public" : "private";
    }
}
