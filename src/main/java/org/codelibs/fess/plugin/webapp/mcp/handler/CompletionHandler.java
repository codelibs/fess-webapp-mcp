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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.entity.SearchRequestParams.SearchRequestType;
import org.codelibs.fess.helper.SuggestHelper;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.suggest.entity.SuggestItem;
import org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder;
import org.codelibs.fess.suggest.request.suggest.SuggestResponse;
import org.codelibs.fess.util.ComponentUtil;

/**
 * The {@code completion/complete} handler: autocomplete for prompt arguments, backed by Fess
 * suggest for {@code query} and a static prefix filter for {@code advanced_search.sort}.
 *
 * <p>
 * Not a {@code CacheableResult}: {@code CompleteResult} is not in the schema's cacheable-result
 * union, so this result never carries {@code ttlMs} or {@code cacheScope}.
 * </p>
 */
public class CompletionHandler implements McpMethodHandler {

    /**
     * Creates a {@code completion/complete} handler.
     */
    public CompletionHandler() {
        // nothing to initialize
    }

    /** The maximum number of completion values returned, per the MCP completion spec. */
    protected static final int MAX_VALUES = 100;

    /** Static sort candidate values for {@code advanced_search.sort} completion. */
    protected static final List<String> SORT_VALUES =
            List.of("score.desc", "score.asc", "last_modified.desc", "last_modified.asc", "create_timestamp.desc", "create_timestamp.asc");

    @Override
    public String getMethod() {
        return "completion/complete";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(final McpCallContext context) {
        final Map<String, Object> params = context.getParams();

        final Object refObj = params.get("ref");
        if (!(refObj instanceof Map)) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required parameter: ref");
        }
        final Map<String, Object> ref = (Map<String, Object>) refObj;

        final Object argumentObj = params.get("argument");
        if (!(argumentObj instanceof Map)) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required parameter: argument");
        }
        final Map<String, Object> argument = (Map<String, Object>) argumentObj;

        final String refType = ref.get("type") instanceof final String s ? s : null;
        final String argName = argument.get("name") instanceof final String s ? s : null;
        final String argValueRaw = argument.get("value") instanceof final String s ? s : null;
        final String argValue = argValueRaw == null ? "" : argValueRaw;

        if ("ref/prompt".equals(refType)) {
            final String promptName = ref.get("name") instanceof final String s ? s : null;

            // query argument on basic_search / advanced_search -> Fess suggest
            if (("basic_search".equals(promptName) || "advanced_search".equals(promptName)) && "query".equals(argName)) {
                if (argValue.isEmpty()) {
                    return buildCompletionResult(List.of(), 0, false);
                }
                return completeViaSuggest(argValue);
            }

            // advanced_search.sort -> static prefix filter
            if ("advanced_search".equals(promptName) && "sort".equals(argName)) {
                final List<String> matches = SORT_VALUES.stream().filter(v -> v.startsWith(argValue)).collect(Collectors.toList());
                return buildCompletionResult(matches, matches.size(), false);
            }

            // advanced_search.num or unknown prompt/argument -> empty values
            return buildCompletionResult(List.of(), 0, false);
        }

        // ref/resource or any other ref type -> empty values
        return buildCompletionResult(List.of(), 0, false);
    }

    /**
     * Executes Fess suggest and returns a capped completion result.
     *
     * @param query the autocomplete input value to forward to Fess suggest
     * @return the {@code completion/complete} response map with {@code values}, {@code total},
     *         and {@code hasMore}
     */
    protected Map<String, Object> completeViaSuggest(final String query) {
        final SuggestResponse suggestResponse = buildSuggestRequest(query).execute().getResponse();

        final List<String> values = new ArrayList<>();
        if (suggestResponse.getItems() != null) {
            for (final SuggestItem item : suggestResponse.getItems()) {
                values.add(item.getText());
            }
        }

        final List<String> capped = values.size() > MAX_VALUES ? values.subList(0, MAX_VALUES) : values;
        final int total = (int) suggestResponse.getTotal();
        final boolean hasMore = total > capped.size();
        return buildCompletionResult(capped, total, hasMore);
    }

    /**
     * Configures the suggest request backing {@code completion/complete}.
     *
     * @param query the autocomplete input value
     * @return the configured builder, ready to execute
     */
    protected SuggestRequestBuilder buildSuggestRequest(final String query) {
        final SuggestRequestBuilder builder = newSuggestRequestBuilder();
        builder.setQuery(query);
        builder.setSize(MAX_VALUES);
        builder.addKind(SuggestItem.Kind.QUERY.toString());
        builder.addKind(SuggestItem.Kind.DOCUMENT.toString());
        getCallerRoles().forEach(builder::addRole);
        return builder;
    }

    /**
     * Creates an unconfigured suggest request builder. Overridable so
     * {@link #buildSuggestRequest} can be asserted on without a container.
     *
     * @return a fresh builder from Fess's suggester
     */
    protected SuggestRequestBuilder newSuggestRequestBuilder() {
        return getSuggestHelper().suggester().suggest();
    }

    /**
     * Returns the roles the current caller may search with.
     *
     * @return the caller's roles; never null, possibly empty
     */
    protected Set<String> getCallerRoles() {
        return ComponentUtil.getRoleQueryHelper().build(SearchRequestType.SUGGEST);
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
     * Builds the {@code completion/complete} response envelope.
     *
     * @param values the candidate completion values (capped at {@value #MAX_VALUES})
     * @param total the total number of candidates known to the server
     * @param hasMore whether more candidates exist beyond the returned values
     * @return a mutable map with {@code completion.values}, {@code completion.total}, and
     *         {@code completion.hasMore}
     */
    protected Map<String, Object> buildCompletionResult(final List<String> values, final int total, final boolean hasMore) {
        final List<String> capped = values.size() > MAX_VALUES ? values.subList(0, MAX_VALUES) : values;
        final int reportedTotal = Math.max(total, capped.size());
        final boolean reportedHasMore = hasMore || reportedTotal > capped.size();

        final Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("values", capped);
        completion.put("total", reportedTotal);
        completion.put("hasMore", reportedHasMore);

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("completion", completion);
        return result;
    }
}
