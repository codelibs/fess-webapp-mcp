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

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.entity.SearchRequestParams.SearchRequestType;
import org.codelibs.fess.helper.SuggestHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.suggest.entity.SuggestItem;
import org.codelibs.fess.suggest.exception.SuggesterException;
import org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder;
import org.codelibs.fess.suggest.request.suggest.SuggestResponse;
import org.codelibs.fess.util.ComponentUtil;

/**
 * The {@code suggest} MCP tool: query autocomplete via Fess's {@link SuggestHelper}.
 */
public class SuggestTool implements McpTool {

    private static final Logger logger = LogManager.getLogger(SuggestTool.class);

    /** Returned when the suggest index cannot answer: a server-side failure, not an empty result. */
    static final String UNAVAILABLE_TEXT = "Suggestions are unavailable: the server could not complete the suggest request. "
            + "This is a server-side failure, not an empty result; retry later.";

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
        // getInputSchema() declares q as a string and is applied nowhere, so this cast used to
        // fail with a raw JVM message ("class java.lang.Integer cannot be cast to class
        // java.lang.String ...") reported as an isError:true result, instead of the -32602 the
        // MCP specification requires ("Servers MUST: Validate all tool inputs"). The type is
        // checked before the cast; whether the (correctly-typed) value is usable stays below.
        //
        // The two exception types here are both HTTP 200 / -32602 on the wire: ToolsCallHandler
        // propagates McpError unchanged and bridges McpApiException into exactly that same
        // McpError. McpError is the go-forward contract, so new checks use it; the existing
        // McpApiException below is left alone because migrating it is the separate, deliberately
        // deferred piece of work its "pre-2026-07-28 exception type" comment describes.
        //
        // num is deliberately not type-checked: resolveSuggestSize accepts any type by design
        // (Number directly, anything else via Integer.parseInt(toString()) with the
        // NumberFormatException caught and a documented fallback to 10), so no cast can fail.
        final Object queryArg = arguments.get("q");
        if (queryArg != null && !(queryArg instanceof String)) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Invalid type for parameter: q (expected a string)");
        }
        final String query = (String) queryArg;
        if (query == null || query.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: q");
        }

        final List<String> suggestions;
        try {
            suggestions = executeSuggest(query, arguments.get("num"));
        } catch (final SuggesterException e) {
            // The suggest index could not answer, most often because the search engine is down.
            // ToolsCallHandler's catch-all would report an opaque correlation id, which an agent
            // cannot tell from a bug, and write a stack trace per call for as long as the outage
            // lasts. Say that the server failed and that it is not an empty answer; the operator
            // gets one line with the cause.
            if (logger.isWarnEnabled()) {
                logger.warn("[MCP] suggest could not be answered: {}", rootCauseMessage(e));
            }
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] suggest failure", e);
            }
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", UNAVAILABLE_TEXT)));
            result.put("isError", true);
            return result;
        }

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

        final SuggestResponse suggestResponse = buildSuggestRequest(query, num).execute().getResponse();

        final List<String> texts = new ArrayList<>();
        if (suggestResponse.getItems() != null) {
            for (final SuggestItem item : suggestResponse.getItems()) {
                texts.add(item.getText());
            }
        }
        return texts;
    }

    /**
     * Returns the message of the innermost cause, which names what actually failed (for example
     * the refused connection) rather than the wrapper's "Failed to execute request".
     *
     * @param e the failure
     * @return the innermost non-null message, or the failure's own class name
     */
    static String rootCauseMessage(final Throwable e) {
        String message = e.getClass().getName();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                message = t.getMessage();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return message;
    }

    /**
     * Configures the suggest request this tool issues.
     *
     * @param query the non-empty query prefix
     * @param num the already-resolved number of suggestions to ask for
     * @return the configured builder, ready to execute
     */
    protected SuggestRequestBuilder buildSuggestRequest(final String query, final int num) {
        final SuggestRequestBuilder builder = newSuggestRequestBuilder();
        builder.setQuery(query);
        builder.setSize(num);
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
