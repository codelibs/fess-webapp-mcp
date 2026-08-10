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

import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;

/**
 * The {@code resources/list} handler.
 */
public class ResourcesListHandler extends AbstractCacheableHandler {

    /** The hot-reloadable system property key holding this result's TTL. */
    protected static final String TTL_CONFIG_KEY = "mcp.cache.list.ttl.ms";

    /** Default TTL when {@value #TTL_CONFIG_KEY} is unset or unparseable. */
    protected static final long DEFAULT_TTL_MS = 3_600_000L;

    /**
     * Creates a {@code resources/list} handler.
     */
    public ResourcesListHandler() {
        super(TTL_CONFIG_KEY, DEFAULT_TTL_MS);
    }

    @Override
    public String getMethod() {
        return "resources/list";
    }

    @Override
    public Map<String, Object> handle(final McpCallContext context) {
        rejectCursor(context);

        final Map<String, Object> indexResource = new LinkedHashMap<>();
        indexResource.put("uri", "fess://index/stats");
        indexResource.put("name", "Index Statistics");
        indexResource.put("description", "Fess index statistics and configuration information");
        indexResource.put("mimeType", "application/json");

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", List.of(indexResource));
        putCacheHints(result, context);
        return result;
    }

    @Override
    protected String getCacheScope(final McpCallContext context) {
        // See ToolsListHandler#getCacheScope: unconditionally public until Task 13 wires
        // mcp.auth.mode in, in lockstep with hiding get_index_stats from unauthorized callers.
        return "public";
    }
}
