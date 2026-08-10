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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.entity.FacetInfo;
import org.codelibs.fess.entity.GeoInfo;
import org.codelibs.fess.entity.HighlightInfo;
import org.codelibs.fess.entity.SearchRenderData;
import org.codelibs.fess.entity.SearchRequestParams;
import org.codelibs.fess.helper.SearchHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
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
        return Map.of("type", "object");
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
        final SearchRequestParams reqParams = buildRequestParams(arguments);

        // Execute search
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Executing search: query='{}', start={}, num={}, sort={}", reqParams.getQuery(),
                    reqParams.getStartPosition(), reqParams.getPageSize(), reqParams.getSort());
        }
        final SearchRenderData data = new SearchRenderData();
        getSearchHelper().search(reqParams, data, OptionalThing.empty());
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Search completed: resultCount={}", data.getDocumentItems() != null ? data.getDocumentItems().size() : 0);
        }

        // Build MCP-compliant response with multiple content entries
        final List<Map<String, Object>> contents = new ArrayList<>();
        final List<Map<String, Object>> documentItems = processDocumentItems(data.getDocumentItems());

        int index = 1;
        for (final Map<String, Object> doc : documentItems) {
            contents.add(createDocumentContent(doc, index++));
        }

        return Map.of("content", contents);
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
                final int fragmentSize = getFessConfig().getSystemPropertyAsInt("mcp.highlight.fragment.size", 500);
                final int numOfFragments = getFessConfig().getSystemPropertyAsInt("mcp.highlight.num.of.fragments", 3);
                return new HighlightInfo().fragmentSize(fragmentSize).numOfFragments(numOfFragments);
            }

            @Override
            public String getSort() {
                return (String) paramMap.get("sort");
            }

            @Override
            public int getStartPosition() {
                final Object value = paramMap.get("start");
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
                final Object value = paramMap.get("num");
                try {
                    if (value != null) {
                        final int num = value instanceof final Number n ? n.intValue() : Integer.parseInt(value.toString());
                        if (num > getFessConfig().getPagingSearchPageMaxSizeAsInteger().intValue() || num <= 0) {
                            return getFessConfig().getPagingSearchPageMaxSizeAsInteger();
                        }
                        return num;
                    }
                } catch (final NumberFormatException e) {
                    logger.debug("Failed to parse {}", value, e);
                }
                return getDefaultPageSize();
            }

            @Override
            public int getOffset() {
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
        return getFessConfig().getSystemPropertyAsInt("mcp.default.page.size", 3);
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
