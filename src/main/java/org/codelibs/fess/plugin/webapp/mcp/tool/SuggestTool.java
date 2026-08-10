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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.helper.SuggestHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.suggest.entity.SuggestItem;
import org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder;
import org.codelibs.fess.suggest.request.suggest.SuggestResponse;
import org.codelibs.fess.util.ComponentUtil;

/**
 * The {@code suggest} MCP tool: query autocomplete via Fess's {@link SuggestHelper}.
 */
public class SuggestTool implements McpTool {

    private static final Logger logger = LogManager.getLogger(SuggestTool.class);

    /**
     * Creates a {@code suggest} tool.
     */
    public SuggestTool() {
        // nothing to initialize
    }

    @Override
    public String getName() {
        return "suggest";
    }

    @Override
    public String getDescription() {
        return "Get autocomplete suggestions for a search query prefix";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("q", Map.of("type", "string", "description", "query prefix for autocomplete"));
        properties.put("num", Map.of("type", "integer", "description", "number of suggestions"));

        final Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("q"));
        return schema;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        final Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("type", "object");
        suggestion.put("properties", Map.of("text", Map.of("type", "string")));
        suggestion.put("required", List.of("text"));
        suggestion.put("additionalProperties", false);

        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("suggestions", Map.of("type", "array", "items", suggestion)));
        schema.put("required", List.of("suggestions"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> getAnnotations() {
        return Map.of("title", "Suggest", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false);
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Collections.emptySet();
    }

    @Override
    public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
        final String query = (String) arguments.get("q");
        if (query == null || query.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: q");
        }

        final List<String> suggestions = executeSuggest(query, arguments.get("num"));

        final List<Map<String, Object>> contents = new ArrayList<>();
        for (final String text : suggestions) {
            contents.add(Map.of("type", "text", "text", text));
        }
        if (contents.isEmpty()) {
            contents.add(Map.of("type", "text", "text", "No suggestions found for: " + query));
        }

        final List<Map<String, Object>> structuredSuggestions =
                suggestions.stream().map(text -> Map.<String, Object> of("text", text)).collect(Collectors.toList());

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", contents);
        result.put("structuredContent", Map.of("suggestions", structuredSuggestions));
        return result;
    }

    /**
     * Executes the suggest request and returns the suggestion texts.
     * <p>
     * This is the seam a container-free test overrides to exercise {@link #call} end to end
     * without a DI container: everything below this point (resolving the effective size via
     * {@link #getFessConfig()} and running the request via {@link #getSuggestHelper()}) needs
     * one.
     * </p>
     *
     * @param query the non-empty query prefix
     * @param numArg the raw {@code num} argument value, as passed to {@link #resolveSuggestSize}
     * @return the suggestion texts, in response order; never null
     */
    protected List<String> executeSuggest(final String query, final Object numArg) {
        final FessConfig fessConfig = getFessConfig();
        final int maxPageSize = fessConfig.getPagingSearchPageMaxSizeAsInteger().intValue();
        final int num = resolveSuggestSize(numArg, maxPageSize);

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Executing suggest: query='{}', num={}", query, num);
        }

        final SuggestRequestBuilder builder = getSuggestHelper().suggester().suggest();
        builder.setQuery(query);
        builder.setSize(num);
        builder.addKind(SuggestItem.Kind.QUERY.toString());
        builder.addKind(SuggestItem.Kind.DOCUMENT.toString());

        final SuggestResponse suggestResponse = builder.execute().getResponse();

        final List<String> texts = new ArrayList<>();
        if (suggestResponse.getItems() != null) {
            for (final SuggestItem item : suggestResponse.getItems()) {
                texts.add(item.getText());
            }
        }
        return texts;
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
     * Returns the suggest helper component used to execute suggest requests.
     *
     * @return the suggest helper
     */
    protected SuggestHelper getSuggestHelper() {
        return ComponentUtil.getSuggestHelper();
    }

    /**
     * Resolves the requested suggest size, applying defaults and the configured page-size cap.
     * <p>
     * Parsing rules:
     * <ul>
     *   <li>{@code null} or unparseable input -&gt; default 10</li>
     *   <li>{@code Number} -&gt; intValue</li>
     *   <li>other -&gt; {@link Integer#parseInt}(toString())</li>
     *   <li>result &lt;= 0 -&gt; default 10</li>
     *   <li>result &gt; {@code maxPageSize} -&gt; capped at {@code maxPageSize}</li>
     * </ul>
     *
     * @param numObj the raw {@code num} argument value
     * @param maxPageSize the configured maximum page size (cap)
     * @return the effective suggest size within [1, maxPageSize]
     */
    protected int resolveSuggestSize(final Object numObj, final int maxPageSize) {
        int num = 10;
        if (numObj instanceof final Number n) {
            num = n.intValue();
        } else if (numObj != null) {
            try {
                num = Integer.parseInt(numObj.toString());
            } catch (final NumberFormatException e) {
                num = 10;
            }
        }
        // Fall back to default for non-positive values; cap at configured max.
        if (num <= 0) {
            num = 10;
        }
        if (num > maxPageSize) {
            num = maxPageSize;
        }
        return num;
    }
}
