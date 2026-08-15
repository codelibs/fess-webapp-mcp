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
 * The {@code prompts/list} handler.
 */
public class PromptsListHandler extends AbstractCacheableHandler {

    /** The hot-reloadable system property key holding this result's TTL. */
    protected static final String TTL_CONFIG_KEY = "mcp.cache.list.ttl.ms";

    /** Default TTL when {@value #TTL_CONFIG_KEY} is unset or unparseable. */
    protected static final long DEFAULT_TTL_MS = 3_600_000L;

    /**
     * Creates a {@code prompts/list} handler.
     */
    public PromptsListHandler() {
        super(TTL_CONFIG_KEY, DEFAULT_TTL_MS);
    }

    @Override
    public String getMethod() {
        return "prompts/list";
    }

    @Override
    public Map<String, Object> handle(final McpCallContext context) {
        rejectCursor(context);

        final Map<String, Object> basicSearchPrompt = new LinkedHashMap<>();
        basicSearchPrompt.put("name", "basic_search");
        basicSearchPrompt.put("description", "Perform a basic search with a query string");
        final Map<String, Object> basicSearchArg = new LinkedHashMap<>();
        basicSearchArg.put("name", "query");
        basicSearchArg.put("description", "The search query");
        basicSearchArg.put("required", true);
        basicSearchPrompt.put("arguments", List.of(basicSearchArg));

        final Map<String, Object> advancedSearchPrompt = new LinkedHashMap<>();
        advancedSearchPrompt.put("name", "advanced_search");
        advancedSearchPrompt.put("description", "Perform an advanced search with filters and sorting");
        final Map<String, Object> advQueryArg = new LinkedHashMap<>();
        advQueryArg.put("name", "query");
        advQueryArg.put("description", "The search query");
        advQueryArg.put("required", true);
        final Map<String, Object> advSortArg = new LinkedHashMap<>();
        advSortArg.put("name", "sort");
        advSortArg.put("description", "Sort order (e.g., 'score.desc', 'last_modified.desc')");
        advSortArg.put("required", false);
        final Map<String, Object> advNumArg = new LinkedHashMap<>();
        advNumArg.put("name", "num");
        advNumArg.put("description", "Number of results to return");
        advNumArg.put("required", false);
        advancedSearchPrompt.put("arguments", List.of(advQueryArg, advSortArg, advNumArg));

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("prompts", List.of(basicSearchPrompt, advancedSearchPrompt));
        putCacheHints(result, context);
        return result;
    }

    @Override
    protected String getCacheScope(final McpCallContext context) {
        // Fixed public per 6.2's table: prompts/list has no auth-mode-dependent rule.
        return "public";
    }
}
