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
package org.codelibs.fess.plugin.webapp.mcp.tool;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.TotalHits;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.client.SearchEngineClient;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.util.ComponentUtil;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.search.SearchHits;

/**
 * The {@code get_index_stats} MCP tool: index document count, configuration, and JVM
 * memory information.
 */
public class IndexStatsTool implements McpTool {

    private static final Logger logger = LogManager.getLogger(IndexStatsTool.class);

    /** System property key for the comma-separated list of permissions required by this tool. */
    protected static final String PERMISSIONS_PROPERTY = "mcp.tools.index_stats.permissions";

    /** The default permission requirement, used when {@value #PERMISSIONS_PROPERTY} is unset. */
    protected static final String DEFAULT_PERMISSIONS = "Radmin-api";

    /**
     * Creates a {@code get_index_stats} tool.
     */
    public IndexStatsTool() {
        // nothing to initialize
    }

    @Override
    public String getName() {
        return "get_index_stats";
    }

    @Override
    public String getDescription() {
        return "Get index statistics and information";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        final Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        return schema;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        final Map<String, Object> index = new LinkedHashMap<>();
        index.put("type", "object");
        index.put("properties", Map.of("index_name", Map.of("type", "string"), "document_count", Map.of("type", "integer"), "error",
                Map.of("type", "string")));
        // document_count is set on both the success and the catch(Exception) branch of
        // collectIndexStats(); index_name and error are each set on only one of those branches.
        index.put("required", List.of("document_count"));
        index.put("additionalProperties", false);

        final Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "object");
        config.put("properties", Map.of("max_page_size", Map.of("type", "integer")));
        config.put("required", List.of("max_page_size"));
        config.put("additionalProperties", false);

        final Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("type", "object");
        memory.put("properties", Map.of("total_bytes", Map.of("type", "integer"), "free_bytes", Map.of("type", "integer"), "used_bytes",
                Map.of("type", "integer"), "max_bytes", Map.of("type", "integer")));
        memory.put("required", List.of("total_bytes", "free_bytes", "used_bytes", "max_bytes"));
        memory.put("additionalProperties", false);

        final Map<String, Object> system = new LinkedHashMap<>();
        system.put("type", "object");
        system.put("properties", Map.of("memory", memory));
        system.put("required", List.of("memory"));
        system.put("additionalProperties", false);

        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("index", index, "config", config, "system", system));
        schema.put("required", List.of("index", "config", "system"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> getAnnotations() {
        return Map.of("title", "Get Index Statistics", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false);
    }

    /**
     * Returns the configured permissions required to read index statistics.
     *
     * <p>
     * This is metadata used to advertise (and, in a later task, enforce) the gate. It is not
     * defensive against a missing DI container: {@code McpApiManager} only ever runs inside the
     * Fess webapp, where the container is always up by the time a tool is invoked, so a
     * container-not-available failure here would indicate a real bug, not an operating
     * condition to mask. Unit tests that call this without a container are expected to override
     * {@link #getIndexStatsPermissions()}.
     * </p>
     *
     * @return the required permissions; may be empty when the setting is explicitly blank, which
     *         disables the gate
     */
    @Override
    public Set<String> getRequiredPermissions() {
        final String value = getIndexStatsPermissions();
        if (value == null || value.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /**
     * Returns the configured permissions required to read index statistics.
     *
     * @return a comma-separated list of encoded Fess permissions; blank disables the gate
     */
    protected String getIndexStatsPermissions() {
        return getFessConfig().getSystemProperty(PERMISSIONS_PROPERTY, DEFAULT_PERMISSIONS);
    }

    @Override
    public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Retrieving index statistics");
        }
        try {
            final Map<String, Object> stats = collectIndexStats();
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Index statistics collected: {}", stats);
            }

            // Return MCP-compliant response with content array
            final String jsonResult = JsonXContent.contentBuilder().map(stats).toString();
            final Map<String, Object> content = new HashMap<>();
            content.put("type", "text");
            content.put("text", jsonResult);

            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(content));
            // Same data as the text block above, not re-derived, with nulls stripped so it
            // conforms to getOutputSchema() (e.g. the "index.error" message can be null).
            result.put("structuredContent", stripNulls(stats));
            return result;
        } catch (final IOException e) {
            throw new McpApiException(ErrorCode.InternalError, "Failed to serialize index stats: " + e.getMessage());
        }
    }

    /**
     * Recursively removes {@code null}-valued entries from a map, so it can be used as (part of)
     * {@code structuredContent} conforming to {@link #getOutputSchema()}.
     * <p>
     * {@link #collectIndexStats()} can put a {@code null} {@code index.error} message (an
     * exception with no message), which {@code structuredContent} must not carry: the schema
     * does not mark {@code error} as {@code required}, but a {@code null} value for a
     * present key is not a valid JSON Schema {@code string} either.
     * </p>
     *
     * @param source the map to strip; not mutated
     * @return a new map with the same non-null entries; nested maps are stripped recursively
     */
    protected Map<String, Object> stripNulls(final Map<String, Object> source) {
        final Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof final Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> typedNested = (Map<String, Object>) nested;
                result.put(key, stripNulls(typedNested));
            } else if (value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    /**
     * Collects index statistics including document count, configuration, and system information.
     *
     * @return a map containing organized statistics data
     */
    public Map<String, Object> collectIndexStats() {
        final Map<String, Object> stats = new LinkedHashMap<>();
        final FessConfig fessConfig = getFessConfig();

        // 1. Index information
        final Map<String, Object> indexInfo = new LinkedHashMap<>();
        try {
            final String indexName = fessConfig.getIndexDocumentSearchIndex();
            indexInfo.put("index_name", indexName);

            final SearchResponse response =
                    getSearchEngineClient().prepareSearch(indexName).setTrackTotalHits(true).setSize(0).execute().actionGet();
            final SearchHits hits = response.getHits();
            final TotalHits totalHits = hits.getTotalHits();
            final long documentCount = totalHits != null ? totalHits.value() : 0;
            indexInfo.put("document_count", documentCount);
        } catch (final Exception e) {
            logger.warn("Failed to get index stats: {}", e.getMessage());
            indexInfo.put("document_count", -1);
            indexInfo.put("error", e.getMessage());
        }
        stats.put("index", indexInfo);

        // 2. Configuration information
        final Map<String, Object> configInfo = new LinkedHashMap<>();
        configInfo.put("max_page_size", fessConfig.getPagingSearchPageMaxSizeAsInteger());
        stats.put("config", configInfo);

        // 3. System information
        final Map<String, Object> systemInfo = new LinkedHashMap<>();
        final Runtime runtime = Runtime.getRuntime();
        final Map<String, Object> memoryInfo = new LinkedHashMap<>();
        memoryInfo.put("total_bytes", runtime.totalMemory());
        memoryInfo.put("free_bytes", runtime.freeMemory());
        memoryInfo.put("used_bytes", runtime.totalMemory() - runtime.freeMemory());
        memoryInfo.put("max_bytes", runtime.maxMemory());
        systemInfo.put("memory", memoryInfo);
        stats.put("system", systemInfo);

        return stats;
    }

    /**
     * Returns the Fess configuration component.
     *
     * @return the Fess configuration
     */
    protected FessConfig getFessConfig() {
        return ComponentUtil.getFessConfig();
    }

    /**
     * Returns the search engine client component used to query document counts.
     *
     * @return the search engine client
     */
    protected SearchEngineClient getSearchEngineClient() {
        return ComponentUtil.getSearchEngineClient();
    }
}
