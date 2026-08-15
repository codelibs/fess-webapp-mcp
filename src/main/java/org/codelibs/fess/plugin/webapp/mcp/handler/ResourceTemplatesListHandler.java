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
 * The {@code resources/templates/list} handler.
 */
public class ResourceTemplatesListHandler extends AbstractCacheableHandler {

    /** The hot-reloadable system property key holding this result's TTL. */
    protected static final String TTL_CONFIG_KEY = "mcp.cache.list.ttl.ms";

    /** Default TTL when {@value #TTL_CONFIG_KEY} is unset or unparseable. */
    protected static final long DEFAULT_TTL_MS = 3_600_000L;

    /**
     * Creates a {@code resources/templates/list} handler.
     */
    public ResourceTemplatesListHandler() {
        super(TTL_CONFIG_KEY, DEFAULT_TTL_MS);
    }

    @Override
    public String getMethod() {
        return "resources/templates/list";
    }

    @Override
    public Map<String, Object> handle(final McpCallContext context) {
        rejectCursor(context);

        final Map<String, Object> docTemplate = new LinkedHashMap<>();
        docTemplate.put("uriTemplate", "fess://document/{doc_id}");
        docTemplate.put("name", "Document by ID");
        docTemplate.put("description", "Retrieve a Fess document by its document ID");
        docTemplate.put("mimeType", "application/json");

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceTemplates", List.of(docTemplate));
        putCacheHints(result, context);
        return result;
    }

    @Override
    protected String getCacheScope(final McpCallContext context) {
        // Fixed public per 6.2's table: unlike tools/list and resources/list, this method's
        // cacheScope has no auth-mode-dependent rule.
        return "public";
    }
}
