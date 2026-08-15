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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.entity.FacetInfo;
import org.codelibs.fess.entity.GeoInfo;
import org.codelibs.fess.entity.HighlightInfo;
import org.codelibs.fess.entity.SearchRenderData;
import org.codelibs.fess.entity.SearchRequestParams;
import org.codelibs.fess.helper.SearchHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.McpSystemProperties;
import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.optional.OptionalThing;

/**
 * The {@code search} MCP tool: full-text search via Fess's {@link SearchHelper}.
 */
public class SearchTool implements McpTool {

    private static final Logger logger = LogManager.getLogger(SearchTool.class);

    /**
     * Creates a {@code search} tool.
     */
    public SearchTool() {
        // nothing to initialize
    }

    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getDescription() {
        return "Search documents via Fess. Query syntax is similar to Lucene: " + "multiple terms are combined with AND by default, "
                + "use OR explicitly for OR search (e.g., \"term1 OR term2\"), " + "use quotes for phrase search, use - for exclusion.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        final Map<String, Object> fieldsLabel = new HashMap<>();
        fieldsLabel.put("type", "array");
        fieldsLabel.put("description", "labels to return");

        final Map<String, Object> fieldsProperties = new HashMap<>();
        fieldsProperties.put("label", fieldsLabel);

        final Map<String, Object> fields = new HashMap<>();
        fields.put("type", "object");
        fields.put("description", "field filters, keyed by field name, e.g. {\"label\": [\"label1\"]}");
        fields.put("properties", fieldsProperties);

        final Map<String, Object> properties = new HashMap<>();
        properties.put("q", Map.of("type", "string", "description", "query string"));
        properties.put("start", Map.of("type", "integer", "description", "start position"));
        properties.put("offset", Map.of("type", "integer", "description", "offset (alias of start)"));
        properties.put("num", Map.of("type", "integer", "description", "number of results"));
        properties.put("sort", Map.of("type", "string", "description", "sort order"));
        properties.put("fields", fields);
        properties.put("lang", Map.of("type", "string", "description", "language"));
        properties.put("as", Map.of("type", "object", "description",
                "advanced search conditions, keyed by condition name, e.g. {\"sitesearch\": [\"example.com\"]}"));
        properties.put("ex_q", Map.of("type", "array", "description", "extra queries", "items", Map.of("type", "string")));
        properties.put("sdh", Map.of("type", "string", "description", "similar document hash"));

        final Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("q"));
        return schema;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        final Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("type", "object");
        hit.put("properties", Map.of("title", Map.of("type", "string"), "url", Map.of("type", "string"), "score", Map.of("type", "number"),
                "content_description", Map.of("type", "string")));
        // No field is guaranteed present on every Fess document item: title/url can be
        // entirely absent from _source for a malformed or partially-indexed document, and score
        // is absent when rank fusion has no BM25 branch (e.g. kNN-only semantic search).
        // content_description is kept optional out of the same caution, even though
        // ViewHelper.getContentDescription() never returns null on the current single-searcher
        // path (it falls back to StringUtil.EMPTY): that guarantee is not verified across every
        // rank-fusion/hybrid search path.
        hit.put("required", List.of());
        hit.put("additionalProperties", false);

        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("hits", Map.of("type", "array", "items", hit)));
        schema.put("required", List.of("hits"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> getAnnotations() {
        return Map.of("title", "Search Documents", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false);
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Collections.emptySet();
    }

    @Override
    public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
        validateArguments(arguments);

        final List<Map<String, Object>> documentItems = executeSearch(arguments);

        // Build MCP-compliant response with multiple content entries, plus structuredContent
        // conforming to getOutputSchema().
        final List<Map<String, Object>> contents = new ArrayList<>();
        final List<Map<String, Object>> hits = new ArrayList<>();
        int index = 1;
        for (final Map<String, Object> doc : documentItems) {
            contents.add(createDocumentContent(doc, index++));
            hits.add(buildHit(doc));
        }

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", contents);
        result.put("structuredContent", Map.of("hits", hits));
        return result;
    }

    /**
     * Rejects arguments that {@link #getInputSchema()} declares but the caller sent with the
     * wrong JSON type, before any of them reaches an unchecked cast.
     * <p>
     * {@code getInputSchema()} is advertised over {@code tools/list} and, until this check
     * existed, applied nowhere: {@code buildRequestParams} casts {@code q}, {@code sort},
     * {@code sdh}, {@code fields}, {@code as}, and {@code ex_q} straight out of the argument map,
     * so {@code {"q": 1}} used to surface as a raw JVM message
     * ({@code "class java.lang.Integer cannot be cast to class java.lang.String ..."}) rather
     * than the {@code -32602} the MCP specification requires ("Servers MUST: Validate all tool
     * inputs"). A missing {@code q} was worse than wrong: {@code getQuery()} simply returned
     * null and the unvalidated request reached {@code SearchHelper} even though the schema marks
     * {@code q} required.
     * </p>
     * <p>
     * This is a top-level type check, not a schema validator, and deliberately stops there.
     * There is no JSON Schema validator on this plugin's classpath, and the build produces a
     * plain {@code maven-jar-plugin} artifact with no shade or assembly step -- the jar ships
     * alone into {@code WEB-INF/plugin} and bundles none of its own dependencies, relying on
     * Fess to supply them at runtime. Introducing a validator is therefore a packaging decision
     * rather than part of this fix. Specifically not checked:
     * </p>
     * <ul>
     *   <li><b>Element types inside {@code fields}/{@code as}/{@code ex_q}.</b> Those need nested,
     *       per-entry validation ({@code fields} is an object of arrays of strings), which is
     *       where a type check turns into a schema engine. A wrong element type still throws
     *       inside the search and is reported as a redacted {@code isError:true} result.</li>
     *   <li><b>{@code start}, {@code offset}, {@code num}.</b> Their accessors already accept any
     *       type deliberately -- {@code Number} directly, anything else via
     *       {@code Integer.parseInt(toString())} with {@code NumberFormatException} caught and a
     *       documented fallback -- so no cast can fail and rejecting a numeric string here would
     *       be a behaviour change, not a fix.</li>
     *   <li><b>{@code lang}.</b> {@code getLanguages()} already handles {@code String[]},
     *       {@code String}, and anything else via {@code toString()}. Same reasoning.</li>
     *   <li><b>An empty {@code q}.</b> The schema requires {@code q} to be present, not to be
     *       non-empty (no {@code minLength}), so what an empty query means is left to Fess.</li>
     * </ul>
     *
     * @param arguments the raw {@code search} tool arguments
     * @throws McpError with {@link ErrorCode#InvalidParams} (-32602) at HTTP 200 when a required
     *         argument is absent or an argument has the wrong JSON type
     */
    protected void validateArguments(final Map<String, Object> arguments) {
        // Absent and explicitly-null are the same thing here: both leave getQuery() returning
        // null, which is exactly the unvalidated request this check exists to stop.
        if (arguments.get("q") == null) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required parameter: q");
        }
        requireTypeIfPresent(arguments, "q", String.class, "a string");
        requireTypeIfPresent(arguments, "sort", String.class, "a string");
        requireTypeIfPresent(arguments, "sdh", String.class, "a string");
        requireTypeIfPresent(arguments, "fields", Map.class, "an object");
        requireTypeIfPresent(arguments, "as", Map.class, "an object");
        requireTypeIfPresent(arguments, "ex_q", List.class, "an array");
    }

    /**
     * Throws {@link McpError} when {@code name} is present with a type other than
     * {@code expectedType}.
     * <p>
     * An absent argument is not this method's business: which arguments are required is decided
     * by {@link #validateArguments}, and every optional one is allowed to be missing. The message
     * names the argument and the expected JSON type but never echoes the value back.
     * </p>
     *
     * @param arguments the raw tool arguments
     * @param name the argument name, as declared in {@link #getInputSchema()}
     * @param expectedType the Java type the JSON type maps to
     * @param expectedTypeName the JSON type name to quote in the error message
     * @throws McpError with {@link ErrorCode#InvalidParams} (-32602) at HTTP 200 on a mismatch
     */
    protected static void requireTypeIfPresent(final Map<String, Object> arguments, final String name, final Class<?> expectedType,
            final String expectedTypeName) {
        final Object value = arguments.get(name);
        if (value != null && !expectedType.isInstance(value)) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams,
                    "Invalid type for parameter: " + name + " (expected " + expectedTypeName + ")");
        }
    }

    /**
     * Executes the search and returns the processed document items.
     * <p>
     * This is the seam a container-free test overrides to exercise {@link #call} end to end
     * without a DI container: everything below this point (building the request params,
     * running the search, and normalising the resulting document items) touches
     * {@link #getSearchHelper()}, which needs one.
     * </p>
     *
     * @param arguments the raw {@code search} tool arguments
     * @return the processed document items, in result order; never null
     */
    protected List<Map<String, Object>> executeSearch(final Map<String, Object> arguments) {
        final SearchRequestParams reqParams = buildRequestParams(arguments);

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Executing search: query='{}', start={}, num={}, sort={}", reqParams.getQuery(),
                    reqParams.getStartPosition(), reqParams.getPageSize(), reqParams.getSort());
        }
        final SearchRenderData data = new SearchRenderData();
        getSearchHelper().search(reqParams, data, OptionalThing.empty());
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Search completed: resultCount={}", data.getDocumentItems() != null ? data.getDocumentItems().size() : 0);
        }

        return processDocumentItems(data.getDocumentItems());
    }

    /**
     * Builds one {@code hits[]} entry of {@code structuredContent} from a processed document
     * item, conforming to {@link #getOutputSchema()}.
     * <p>
     * Only the fields declared by {@code getOutputSchema()} are copied over -- notably, the
     * (possibly large, possibly truncated) raw {@code content} field is deliberately left out,
     * since the text block already carries a formatted view of it. None of {@code title},
     * {@code url}, {@code score}, or {@code content_description} is guaranteed present on a raw
     * Fess document item (a document's {@code _source} can genuinely lack {@code title}/
     * {@code url} entirely, not just be blank), so all four are copied with
     * {@link #putIfNotNull} and omitted -- never fabricated as {@code ""} or copied as
     * {@code null} -- when Fess did not populate them. This intentionally diverges from
     * {@link #createDocumentContent}, which still falls back to {@code ""} for the
     * human-readable text block: that pre-existing fallback is fine for a line of prose, but
     * fabricating a value here would make the schema's guarantees depend on a fallback instead
     * of on what the data actually contains.
     * </p>
     *
     * @param doc the processed document item, as returned by {@link #processDocumentItems}
     * @return a new map with only the schema's declared fields that Fess actually populated
     */
    protected Map<String, Object> buildHit(final Map<String, Object> doc) {
        final Map<String, Object> hit = new LinkedHashMap<>();
        putIfNotNull(hit, "title", doc.get("title"));
        putIfNotNull(hit, "url", doc.get("url"));
        putIfNotNull(hit, "score", doc.get("score"));
        putIfNotNull(hit, "content_description", doc.get("content_description"));
        return hit;
    }

    /**
     * Puts {@code key}-&gt;{@code value} into {@code target} unless {@code value} is null.
     *
     * @param target the map to (maybe) mutate
     * @param key the key to put
     * @param value the value to put, if non-null
     */
    private static void putIfNotNull(final Map<String, Object> target, final String key, final Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * Builds the {@link SearchRequestParams} view {@code call} passes to {@link SearchHelper}.
     * <p>
     * Constructing the returned object does not itself touch the DI container: every Fess
     * config lookup it needs (e.g. for {@code getPageSize()}, {@code getHighlightInfo()},
     * {@code getResponseFields()}) goes through {@link #getFessConfig()} lazily, at the point
     * each accessor is actually called, rather than being resolved once up front. This lets a
     * test build one and assert on a container-free accessor -- notably {@code getType()}, which
     * {@code RoleQueryHelper} relies on returning
     * {@link SearchRequestParams.SearchRequestType#JSON} to apply Fess role filtering to MCP
     * search results -- without needing a container.
     * </p>
     *
     * @param arguments the raw {@code search} tool arguments
     * @return a {@link SearchRequestParams} view over {@code arguments}
     */
    @SuppressWarnings("unchecked")
    protected SearchRequestParams buildRequestParams(final Map<String, Object> arguments) {
        return new SearchRequestParams() {
            private final Map<String, Object> paramMap = arguments;

            @Override
            public String getQuery() {
                return (String) paramMap.get("q");
            }

            @Override
            public Map<String, String[]> getFields() {
                final Map<String, Object> fields = (Map<String, Object>) paramMap.get("fields");
                if (fields != null) {
                    return fields.entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> ((List<String>) e.getValue()).toArray(n -> new String[n])));
                }
                return Collections.emptyMap();
            }

            @Override
            public Map<String, String[]> getConditions() {
                final Map<String, Object> conditions = (Map<String, Object>) paramMap.get("as");
                if (conditions != null) {
                    return conditions.entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getKey,
                                    e -> ((List<?>) e.getValue()).stream().map(Object::toString).toArray(n -> new String[n])));
                }
                return Collections.emptyMap();
            }

            @Override
            public String[] getLanguages() {
                final Object lang = paramMap.get("lang");
                if (lang instanceof final String[] languages) {
                    return languages;
                }
                if (lang != null) {
                    return new String[] { lang.toString() };
                }

                return new String[0];
            }

            @Override
            public GeoInfo getGeoInfo() {
                return null; // Not implemented
            }

            @Override
            public FacetInfo getFacetInfo() {
                return null; // Not implemented
            }

            @Override
            public HighlightInfo getHighlightInfo() {
                final int fragmentSize = McpSystemProperties.getAsInt(getFessConfig(), "mcp.highlight.fragment.size", 500);
                final int numOfFragments = McpSystemProperties.getAsInt(getFessConfig(), "mcp.highlight.num.of.fragments", 3);
                return new HighlightInfo().fragmentSize(fragmentSize).numOfFragments(numOfFragments);
            }

            @Override
            public String getSort() {
                return (String) paramMap.get("sort");
            }

            @Override
            public int getStartPosition() {
                // "offset" is a real alias of "start", not just a documented one.
                //
                // getInputSchema() advertises offset as "offset (alias of start)" over
                // tools/list to every client, but this accessor used to read "start" alone, so
                // the alias resolved to nothing here: a client that paginated with offset kept
                // being served page 1 forever. offset was not entirely inert -- getOffset()
                // below still reads it -- but that accessor feeds a different contract
                // (Fess's RankFusionProcessor shifts the sub-searcher window with it) which
                // only engages with two or more registered searchers *and* on the
                // deep-pagination branch. A stock install has one searcher, so nothing consumed
                // offset at all.
                //
                // "start" wins whenever the caller sent it, even when its value turns out to be
                // unparseable or negative: it is the primary name, and silently substituting
                // the alias for a start the caller got wrong would page through results from
                // somewhere the caller never asked for, with nothing in the response to say so.
                // A key present with an explicit JSON null is treated as absent, matching how
                // validateArguments already treats {"q": null} as a missing q.
                final Object startValue = paramMap.get("start");
                final Object value = startValue != null ? startValue : paramMap.get("offset");
                try {
                    if (value != null) {
                        final int start = value instanceof final Number n ? n.intValue() : Integer.parseInt(value.toString());
                        if (start > -1) {
                            return start;
                        }
                    }
                } catch (final NumberFormatException e) {
                    logger.debug("Failed to parse {}", value, e);
                }
                return getFessConfig().getPagingSearchPageStartAsInteger();
            }

            @Override
            public int getPageSize() {
                // A non-positive num falls back to the default page size, not to the maximum.
                //
                // num <= 0 used to share the "> max" branch and therefore returned the
                // configured MAXIMUM (100 in a stock install): {"num": 0} -- "give me no
                // results" -- produced the largest page this server will ever emit, and so did
                // {"num": -1}. That disagreed with the two neighbouring behaviours it should
                // match: an unparseable num already falls through to getDefaultPageSize()
                // below, and SuggestTool#resolveSuggestSize already documents "result <= 0 ->
                // default". Nothing in the schema or the README ever described the old
                // behaviour, so no caller could have been relying on it deliberately.
                //
                // The upper clamp is deliberately unchanged: num > max is still served as max
                // rather than refused, because the maximum is a server-side protection the
                // caller cannot be expected to know.
                final Object value = paramMap.get("num");
                try {
                    if (value != null) {
                        final int num = value instanceof final Number n ? n.intValue() : Integer.parseInt(value.toString());
                        if (num <= 0) {
                            return getDefaultPageSize();
                        }
                        final int maxPageSize = getFessConfig().getPagingSearchPageMaxSizeAsInteger().intValue();
                        return num > maxPageSize ? maxPageSize : num;
                    }
                } catch (final NumberFormatException e) {
                    logger.debug("Failed to parse {}", value, e);
                }
                return getDefaultPageSize();
            }

            @Override
            public int getOffset() {
                // Deliberately still reads "offset" alone, and deliberately does not alias
                // "start" back the other way. Despite the name, this is not the start position:
                // Fess's RankFusionProcessor uses it to shift each sub-searcher's window on its
                // deep-pagination branch, on top of the start position getStartPosition()
                // already supplied. Aliasing start into it would double-count the caller's
                // paging offset on every hybrid/rank-fusion search.
                final Object value = paramMap.get("offset");
                try {
                    if (value != null) {
                        return value instanceof final Number n ? n.intValue() : Integer.parseInt(value.toString());

                    }
                } catch (final NumberFormatException e) {
                    logger.debug("Failed to parse {}", value, e);
                }
                return 0;
            }

            @Override
            public String[] getExtraQueries() {
                final List<String> exQs = (List<String>) paramMap.get("ex_q");
                return exQs != null ? exQs.toArray(new String[0]) : null;
            }

            @Override
            public Object getAttribute(final String name) {
                return null; // Not implemented
            }

            @Override
            public Locale getLocale() {
                return Locale.ROOT;
            }

            @Override
            public SearchRequestType getType() {
                return SearchRequestType.JSON;
            }

            @Override
            public String getSimilarDocHash() {
                return (String) paramMap.get("sdh");
            }

            @Override
            public String[] getResponseFields() {
                final FessConfig fessConfig = getFessConfig();
                return new String[] { fessConfig.getIndexFieldTitle(), fessConfig.getIndexFieldContent(), fessConfig.getIndexFieldUrl(),
                        fessConfig.getResponseFieldContentDescription() };
            }
        };
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
     * Returns the search helper component used to execute searches.
     *
     * @return the search helper
     */
    protected SearchHelper getSearchHelper() {
        return ComponentUtil.getSearchHelper();
    }

    /**
     * Returns the default page size for search results.
     *
     * @return the configured page size
     */
    protected int getDefaultPageSize() {
        return McpSystemProperties.getAsInt(getFessConfig(), "mcp.default.page.size", 3);
    }

    /**
     * Returns the {@link DocumentFormatter} used to truncate document content.
     *
     * @return a document formatter
     */
    protected DocumentFormatter getDocumentFormatter() {
        return new DocumentFormatter();
    }

    /**
     * Processes document items to convert non-serializable objects (like TextFragment) to strings.
     *
     * @param documentItems the list of document items from search results
     * @return a list of processed document items with serializable values
     */
    protected List<Map<String, Object>> processDocumentItems(final List<Map<String, Object>> documentItems) {
        if (documentItems == null) {
            return Collections.emptyList();
        }
        return documentItems.stream().map(doc -> {
            final Map<String, Object> processedDoc = new LinkedHashMap<>();
            doc.forEach((key, value) -> processedDoc.put(key, processValue(value)));
            return processedDoc;
        }).collect(Collectors.toList());
    }

    /**
     * Processes a single value, converting non-serializable objects to strings.
     *
     * @param value the value to process
     * @return the processed value (String for TextFragment, recursively processed for collections)
     */
    protected Object processValue(final Object value) {
        if (value == null) {
            return null;
        }
        // Handle TextFragment by converting to string
        if (value.getClass().getName().contains("TextFragment")) {
            return value.toString();
        }
        // Handle List
        if (value instanceof final List<?> list) {
            return list.stream().map(this::processValue).collect(Collectors.toList());
        }
        // Handle Map
        if (value instanceof final Map<?, ?> map) {
            final Map<String, Object> processedMap = new LinkedHashMap<>();
            map.forEach((k, v) -> processedMap.put(k.toString(), processValue(v)));
            return processedMap;
        }
        // Handle arrays
        if (value.getClass().isArray()) {
            if (value instanceof final Object[] array) {
                return java.util.Arrays.stream(array).map(this::processValue).collect(Collectors.toList());
            }
        }
        return value;
    }

    /**
     * Creates a document content entry in Markdown format for MCP response.
     *
     * @param doc the processed document
     * @param index the result index (1-based)
     * @return a map containing type and text for MCP content
     */
    protected Map<String, Object> createDocumentContent(final Map<String, Object> doc, final int index) {
        final StringBuilder sb = new StringBuilder();
        final Object score = doc.get("score");
        sb.append("**Title**: ").append(doc.getOrDefault("title", "")).append("\n");
        sb.append("**URL**: ").append(doc.getOrDefault("url", "")).append("\n");
        if (score != null) {
            sb.append("**Score**: ").append(score).append("\n");
        }
        sb.append("\n");

        // Use content_description (highlighted text) if available, fallback to content
        final String contentDescription = String.valueOf(doc.getOrDefault("content_description", ""));
        final String displayContent;
        if (contentDescription.isEmpty() || "null".equals(contentDescription)) {
            // Fallback to raw content with truncation
            final String content = String.valueOf(doc.getOrDefault("content", ""));
            final DocumentFormatter formatter = getDocumentFormatter();
            displayContent = formatter.truncateContent(content, formatter.getContentMaxLength());
        } else {
            // Use highlighted content with tags stripped
            displayContent = stripHighlightTags(contentDescription);
        }
        sb.append(displayContent);

        return Map.of("type", "text", "text", sb.toString());
    }

    /**
     * Removes HTML highlight tags from the given text.
     * Strips both &lt;em&gt; and &lt;strong&gt; tags commonly used for search highlighting.
     *
     * @param text the text containing HTML highlight tags
     * @return the text with highlight tags removed, or empty string if text is null
     */
    protected String stripHighlightTags(final String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("</?(?:em|strong)>", "");
    }
}
