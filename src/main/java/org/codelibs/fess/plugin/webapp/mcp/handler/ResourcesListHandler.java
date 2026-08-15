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

import org.codelibs.fess.plugin.webapp.mcp.auth.PermissionGate;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.tool.IndexStatsTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;

/**
 * The {@code resources/list} handler.
 */
public class ResourcesListHandler extends AbstractCacheableHandler {

    /** The hot-reloadable system property key holding this result's TTL. */
    protected static final String TTL_CONFIG_KEY = "mcp.cache.list.ttl.ms";

    /** Default TTL when {@value #TTL_CONFIG_KEY} is unset or unparseable. */
    protected static final long DEFAULT_TTL_MS = 3_600_000L;

    /** The tool whose {@link McpTool#getRequiredPermissions()} gates the {@code fess://index/stats} resource. */
    private final McpTool indexStatsTool;

    /**
     * Creates a {@code resources/list} handler backed by a fresh {@link IndexStatsTool}.
     */
    public ResourcesListHandler() {
        this(new IndexStatsTool());
    }

    /**
     * Creates a {@code resources/list} handler.
     *
     * @param indexStatsTool the tool whose {@code getRequiredPermissions()} gates
     *            {@code fess://index/stats}; the same primitive {@code ToolsListHandler} and
     *            {@code ToolsCallHandler} gate as {@code get_index_stats}, so both lists agree
     */
    public ResourcesListHandler(final McpTool indexStatsTool) {
        super(TTL_CONFIG_KEY, DEFAULT_TTL_MS);
        this.indexStatsTool = indexStatsTool;
    }

    @Override
    public String getMethod() {
        return "resources/list";
    }

    @Override
    public Map<String, Object> handle(final McpCallContext context) {
        rejectCursor(context);

        final List<Map<String, Object>> resources =
                PermissionGate.isAllowed(indexStatsTool.getRequiredPermissions(), context.getPrincipal())
                        ? List.of(describeIndexStatsResource())
                        : List.of();

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", resources);
        putCacheHints(result, context);
        return result;
    }

    /**
     * Builds the {@code resources/list} descriptor for the {@code fess://index/stats} resource.
     *
     * @return a map with {@code uri}, {@code name}, {@code description}, and {@code mimeType}
     */
    protected Map<String, Object> describeIndexStatsResource() {
        final Map<String, Object> indexResource = new LinkedHashMap<>();
        indexResource.put("uri", "fess://index/stats");
        indexResource.put("name", "Index Statistics");
        indexResource.put("description", "Fess index statistics and configuration information");
        indexResource.put("mimeType", "application/json");
        return indexResource;
    }

    @Override
    protected String getCacheScope(final McpCallContext context) {
        // This result now varies by the caller's authorization once fess://index/stats is
        // gated: see ToolsListHandler#getCacheScope for the identical rationale.
        return AUTH_MODE_NONE.equals(getAuthMode()) ? "public" : "private";
    }
}
