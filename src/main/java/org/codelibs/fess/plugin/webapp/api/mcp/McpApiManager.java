/*
 * Copyright 2012-2024 CodeLibs Project and the Others.
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
package org.codelibs.fess.plugin.webapp.api.mcp;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.Constants;
import org.codelibs.fess.api.BaseApiManager;
import org.codelibs.fess.entity.FacetInfo;
import org.codelibs.fess.entity.GeoInfo;
import org.codelibs.fess.entity.HighlightInfo;
import org.codelibs.fess.entity.SearchRenderData;
import org.codelibs.fess.entity.SearchRequestParams;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.optional.OptionalThing;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.index.query.QueryBuilders;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@code McpApiManager} class is responsible for handling JSON-RPC 2.0 API requests
 * for the MCP (Management Control Protocol) API. It extends the {@code BaseApiManager}
 * and provides methods to process incoming HTTP requests, validate JSON-RPC requests,
 * and dispatch them to the appropriate handlers.
 *
 */
public class McpApiManager extends BaseApiManager {

    private static final Logger logger = LogManager.getLogger(McpApiManager.class);

    private static final int DEFAULT_CONTENT_MAX_LENGTH = 10000;

    /** The MIME type for JSON responses. */
    protected String mimeType = "application/json";

    /**
     * Creates a new MCP API manager with the default path prefix "/mcp".
     */
    public McpApiManager() {
        // JSON-RPC endpoint is /mcp/*
        setPathPrefix("/mcp");
    }

    /**
     * Registers this API manager with the WebApiManagerFactory.
     */
    @PostConstruct
    public void register() {
        if (logger.isInfoEnabled()) {
            logger.info("Load {}", this.getClass().getSimpleName());
        }

        ComponentUtil.getWebApiManagerFactory().add(this);
    }

    @Override
    public boolean matches(final HttpServletRequest request) {
        return request.getServletPath().startsWith(pathPrefix);
    }

    @Override
    public void process(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        writeHeaders(response);
        Object rpcId = null;
        try {
            final Map<String, Object> reqMap = parseRequestBody(request);
            if (logger.isDebugEnabled()) {
                logger.debug("request: {}", reqMap);
            }
            // Retrieve JSON-RPC fields
            final String jsonrpc = (String) reqMap.get("jsonrpc");
            final String method = (String) reqMap.get("method");
            @SuppressWarnings("unchecked")
            final Map<String, Object> params =
                    Optional.ofNullable((Map<String, Object>) reqMap.get("params")).orElse(Collections.emptyMap());
            if (logger.isDebugEnabled()) {
                logger.debug("params: {}", params);
            }

            // Validate the request
            if (!"2.0".equals(jsonrpc) || method == null) {
                throw new McpApiException(ErrorCode.InvalidRequest, "Invalid JSON-RPC request");
            }

            rpcId = reqMap.get("id");

            // Execute the method
            final Object result = dispatchRpcMethod(method, params);
            if (logger.isDebugEnabled()) {
                logger.debug("result: {}", result);
            }

            final Map<String, Object> resMap = new LinkedHashMap<>();
            resMap.put("jsonrpc", "2.0");
            resMap.put("id", rpcId);
            resMap.put("result", result);
            write(JsonXContent.contentBuilder().map(resMap).toString(), mimeType, Constants.UTF_8);
        } catch (final McpApiException mae) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to process request.", mae);
            }
            writeError(rpcId, mae.getCode(), mae.getMessage(), response);
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to process request.", e);
            }
            writeError(rpcId, ErrorCode.InternalError, e.getMessage(), response);
        }
    }

    /**
     * Parses the JSON-RPC request body from the HTTP request.
     *
     * @param request the HTTP servlet request
     * @return a map containing the parsed JSON request body
     * @throws IOException if an I/O error occurs while reading the request
     */
    protected Map<String, Object> parseRequestBody(final HttpServletRequest request) throws IOException {
        return JsonXContent.jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, request.getInputStream())
                .map();
    }

    /**
     * Dispatches a JSON-RPC method call to the appropriate handler.
     *
     * @param method the JSON-RPC method name
     * @param params the method parameters
     * @return the result of the method invocation
     * @throws McpApiException if the method is not found
     */
    protected Object dispatchRpcMethod(final String method, final Map<String, Object> params) {
        return switch (method) {
        case "initialize" -> handleInitialize();
        case "tools/list" -> handleListTools();
        case "tools/call" -> handleInvoke(params);
        case "resources/list" -> handleListResources();
        case "resources/read" -> handleReadResource(params);
        case "prompts/list" -> handleListPrompts();
        case "prompts/get" -> handleGetPrompt(params);
        default -> throw new McpApiException(ErrorCode.MethodNotFound, "Unknown method: " + method);
        };
    }

    @Override
    protected void writeHeaders(final HttpServletResponse response) {
        ComponentUtil.getFessConfig().getApiJsonResponseHeaderList().forEach(e -> response.setHeader(e.getFirst(), e.getSecond()));
    }

    /**
     * Handles the initialization process and returns a map containing
     * the capabilities of the MCP API.
     *
     * @return a map with the following keys:
     *         - "protocolVersion": the MCP protocol version (e.g., "2024-11-05").
     *         - "capabilities": object containing server capabilities including tools, resources, and prompts support.
     *         - "serverInfo": object containing server name and version information.
     */
    protected Map<String, Object> handleInitialize() {
        final Map<String, Object> caps = new HashMap<>();
        caps.put("tools", new HashMap<>());
        caps.put("resources", new HashMap<>());
        caps.put("prompts", new HashMap<>());

        final Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", "fess-mcp-server");
        serverInfo.put("version", "1.0.0");

        return Map.of("protocolVersion", "2024-11-05", "capabilities", caps, "serverInfo", serverInfo);
    }

    /**
     * Handles the creation of a list of tools with their metadata.
     *
     * @return A map with "tools" key containing a list of available tools. Each tool includes:
     *         - "name": The name of the tool (e.g., "search").
     *         - "description": A brief description of the tool (e.g., "Search documents via Fess").
     *         - "inputSchema": A JSON Schema object defining the tool's input parameters.
     */
    protected Map<String, Object> handleListTools() {
        // Search tool
        final Map<String, Object> searchProperties = new HashMap<>();
        searchProperties.put("q", Map.of("type", "string", "description", "query string"));
        searchProperties.put("start", Map.of("type", "integer", "description", "start position"));
        searchProperties.put("offset", Map.of("type", "integer", "description", "offset (alias of start)"));
        searchProperties.put("num", Map.of("type", "integer", "description", "number of results", "default", 3));
        searchProperties.put("sort", Map.of("type", "string", "description", "sort order"));
        searchProperties.put("fields.label", Map.of("type", "array", "description", "labels to return"));
        searchProperties.put("lang", Map.of("type", "string", "description", "language"));
        searchProperties.put("preference", Map.of("type", "string", "description", "preference"));

        final Map<String, Object> searchInputSchema = new HashMap<>();
        searchInputSchema.put("type", "object");
        searchInputSchema.put("properties", searchProperties);
        searchInputSchema.put("required", List.of("q"));

        final Map<String, Object> toolSearch = new HashMap<>();
        toolSearch.put("name", "search");
        toolSearch.put("description",
                "Search documents via Fess. Query syntax is similar to Lucene: " + "multiple terms are combined with AND by default, "
                        + "use OR explicitly for OR search (e.g., \"term1 OR term2\"), "
                        + "use quotes for phrase search, use - for exclusion.");
        toolSearch.put("inputSchema", searchInputSchema);

        // Index stats tool
        final Map<String, Object> statsInputSchema = new HashMap<>();
        statsInputSchema.put("type", "object");
        statsInputSchema.put("properties", new HashMap<>());

        final Map<String, Object> toolStats = new HashMap<>();
        toolStats.put("name", "get_index_stats");
        toolStats.put("description", "Get index statistics and information");
        toolStats.put("inputSchema", statsInputSchema);

        return Map.of("tools", List.of(toolSearch, toolStats));
    }

    /**
     * Handles the invocation of tools via the MCP API by processing the input parameters,
     * executing the requested tool, and returning the results in MCP-compliant format.
     *
     * @param params A map containing the input parameters for the tool call.
     *               It must include "name" (tool name) and "arguments" (tool parameters).
     * @return A map containing the tool execution results in MCP format with "content" array.
     *         Each content item has "type" and "text" fields.
     * @throws McpApiException If required parameters are missing or invalid.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> handleInvoke(final Map<String, Object> params) {
        final String tool = (String) params.get("name");
        if (tool == null || tool.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: name");
        }

        final Map<String, Object> toolParams = (Map<String, Object>) params.get("arguments");
        if (toolParams == null) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: arguments");
        }
        return switch (tool) {
        case "search" -> invokeSearch(toolParams);
        case "get_index_stats" -> invokeGetIndexStats();
        // TODO Add more administrative tools here...
        default -> throw new McpApiException(ErrorCode.InvalidParams, "Unknown tool: " + tool);
        };
    }

    /**
     * Invokes the search tool with the specified parameters.
     *
     * @param params the search parameters including query string (q), pagination (start, num), and other options
     * @return a map containing the search results in MCP-compliant format
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> invokeSearch(final Map<String, Object> params) {
        // Create and populate SearchRequestParams
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final SearchRequestParams reqParams = new SearchRequestParams() {
            private final Map<String, Object> paramMap = params;

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
                return null; // Not implemented
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
                return ComponentUtil.getFessConfig().getPagingSearchPageStartAsInteger();
            }

            @Override
            public int getPageSize() {
                final Object value = paramMap.get("num");
                try {
                    if (value != null) {
                        final int num = value instanceof final Number n ? n.intValue() : Integer.parseInt(value.toString());
                        if (num > fessConfig.getPagingSearchPageMaxSizeAsInteger().intValue() || num <= 0) {
                            return fessConfig.getPagingSearchPageMaxSizeAsInteger();
                        }
                        return num;
                    }
                } catch (final NumberFormatException e) {
                    logger.debug("Failed to parse {}", value, e);
                }
                return 3;
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
                return new String[] { fessConfig.getIndexFieldTitle(), fessConfig.getIndexFieldContent(), fessConfig.getIndexFieldUrl() };
            }
        };

        // Execute search
        final SearchRenderData data = new SearchRenderData();
        ComponentUtil.getSearchHelper().search(reqParams, data, OptionalThing.empty());

        // Build MCP-compliant response with multiple content entries
        final List<Map<String, Object>> contents = new java.util.ArrayList<>();
        final List<Map<String, Object>> documentItems = processDocumentItems(data.getDocumentItems());

        int index = 1;
        for (final Map<String, Object> doc : documentItems) {
            contents.add(createDocumentContent(doc, index++));
        }

        return Map.of("content", contents);
    }

    /**
     * Retrieves index statistics and system information.
     *
     * @return A map containing index statistics in MCP-compliant format with "content" array.
     */
    protected Map<String, Object> invokeGetIndexStats() {
        try {
            final Map<String, Object> stats = collectIndexStats();

            // Return MCP-compliant response with content array
            final String jsonResult = JsonXContent.contentBuilder().map(stats).toString();
            final Map<String, Object> content = new HashMap<>();
            content.put("type", "text");
            content.put("text", jsonResult);
            return Map.of("content", List.of(content));
        } catch (final IOException e) {
            throw new McpApiException(ErrorCode.InternalError, "Failed to serialize index stats: " + e.getMessage());
        }
    }

    /**
     * Collects index statistics including document count, configuration, and system information.
     *
     * @return A map containing organized statistics data
     */
    protected Map<String, Object> collectIndexStats() {
        final Map<String, Object> stats = new LinkedHashMap<>();
        final FessConfig fessConfig = ComponentUtil.getFessConfig();

        // 1. Index information
        final Map<String, Object> indexInfo = new LinkedHashMap<>();
        try {
            final String indexName = fessConfig.getIndexDocumentSearchIndex();
            indexInfo.put("index_name", indexName);

            final org.opensearch.action.search.SearchResponse response =
                    ComponentUtil.getSearchEngineClient().prepareSearch(indexName).setTrackTotalHits(true).setSize(0).execute().actionGet();
            final org.opensearch.search.SearchHits hits = response.getHits();
            final org.apache.lucene.search.TotalHits totalHits = hits.getTotalHits();
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
     * Handles the resources/list request and returns available resources.
     *
     * @return A map with "resources" key containing a list of available resources.
     */
    protected Map<String, Object> handleListResources() {
        final Map<String, Object> indexResource = new HashMap<>();
        indexResource.put("uri", "fess://index/stats");
        indexResource.put("name", "Index Statistics");
        indexResource.put("description", "Fess index statistics and configuration information");
        indexResource.put("mimeType", "application/json");

        return Map.of("resources", List.of(indexResource));
    }

    /**
     * Handles the resources/read request and returns the resource content.
     *
     * @param params the request parameters containing "uri"
     * @return A map with "contents" key containing the resource content
     * @throws McpApiException if the URI is missing or unknown
     */
    protected Map<String, Object> handleReadResource(final Map<String, Object> params) {
        final String uri = (String) params.get("uri");
        if (uri == null || uri.isBlank()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: uri");
        }

        return switch (uri) {
        case "fess://index/stats" -> buildIndexStatsResource();
        default -> throw new McpApiException(ErrorCode.InvalidParams, "Unknown resource: " + uri);
        };
    }

    /**
     * Builds the index stats resource content.
     *
     * @return A map with "contents" key containing the index stats
     */
    protected Map<String, Object> buildIndexStatsResource() {
        try {
            final Map<String, Object> stats = collectIndexStats();
            final String jsonResult = JsonXContent.contentBuilder().map(stats).toString();

            final Map<String, Object> content = new HashMap<>();
            content.put("uri", "fess://index/stats");
            content.put("mimeType", "application/json");
            content.put("text", jsonResult);

            return Map.of("contents", List.of(content));
        } catch (final IOException e) {
            throw new McpApiException(ErrorCode.InternalError, "Failed to serialize index stats: " + e.getMessage());
        }
    }

    /**
     * Handles the prompts/list request and returns available prompts.
     *
     * @return A map with "prompts" key containing a list of available prompts.
     */
    protected Map<String, Object> handleListPrompts() {
        // Basic search prompt
        final Map<String, Object> basicSearchPrompt = new HashMap<>();
        basicSearchPrompt.put("name", "basic_search");
        basicSearchPrompt.put("description", "Perform a basic search with a query string");

        final Map<String, Object> basicSearchArg = new HashMap<>();
        basicSearchArg.put("name", "query");
        basicSearchArg.put("description", "The search query");
        basicSearchArg.put("required", true);

        basicSearchPrompt.put("arguments", List.of(basicSearchArg));

        // Advanced search prompt
        final Map<String, Object> advancedSearchPrompt = new HashMap<>();
        advancedSearchPrompt.put("name", "advanced_search");
        advancedSearchPrompt.put("description", "Perform an advanced search with filters and sorting");

        final Map<String, Object> advQueryArg = new HashMap<>();
        advQueryArg.put("name", "query");
        advQueryArg.put("description", "The search query");
        advQueryArg.put("required", true);

        final Map<String, Object> advSortArg = new HashMap<>();
        advSortArg.put("name", "sort");
        advSortArg.put("description", "Sort order (e.g., 'score.desc', 'last_modified.desc')");
        advSortArg.put("required", false);

        final Map<String, Object> advNumArg = new HashMap<>();
        advNumArg.put("name", "num");
        advNumArg.put("description", "Number of results to return");
        advNumArg.put("required", false);

        advancedSearchPrompt.put("arguments", List.of(advQueryArg, advSortArg, advNumArg));

        return Map.of("prompts", List.of(basicSearchPrompt, advancedSearchPrompt));
    }

    /**
     * Handles the prompts/get request and returns the prompt messages with arguments substituted.
     *
     * @param params the request parameters containing "name" and optional "arguments"
     * @return A map with "messages" key containing the prompt messages
     * @throws McpApiException if the prompt name is missing or unknown
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> handleGetPrompt(final Map<String, Object> params) {
        final String name = (String) params.get("name");
        if (name == null || name.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: name");
        }

        final Map<String, Object> arguments = params.get("arguments") != null ? (Map<String, Object>) params.get("arguments") : Map.of();

        return switch (name) {
        case "basic_search" -> buildBasicSearchPrompt(arguments);
        case "advanced_search" -> buildAdvancedSearchPrompt(arguments);
        default -> throw new McpApiException(ErrorCode.InvalidParams, "Unknown prompt: " + name);
        };
    }

    /**
     * Builds the basic_search prompt messages.
     *
     * @param arguments the prompt arguments
     * @return A map with "messages" key containing the prompt messages
     */
    protected Map<String, Object> buildBasicSearchPrompt(final Map<String, Object> arguments) {
        final String query = (String) arguments.get("query");
        if (query == null || query.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required argument: query");
        }

        final Map<String, Object> content = new HashMap<>();
        content.put("type", "text");
        content.put("text", "Please search for: " + query);

        final Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", content);

        return Map.of("messages", List.of(message));
    }

    /**
     * Builds the advanced_search prompt messages.
     *
     * @param arguments the prompt arguments
     * @return A map with "messages" key containing the prompt messages
     */
    protected Map<String, Object> buildAdvancedSearchPrompt(final Map<String, Object> arguments) {
        final String query = (String) arguments.get("query");
        if (query == null || query.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required argument: query");
        }

        final StringBuilder text = new StringBuilder();
        text.append("Please perform an advanced search with the following parameters:\n");
        text.append("Query: ").append(query);

        final Object sort = arguments.get("sort");
        if (sort != null && !sort.toString().isEmpty()) {
            text.append("\nSort: ").append(sort);
        }

        final Object num = arguments.get("num");
        if (num != null && !num.toString().isEmpty()) {
            text.append("\nNumber of results: ").append(num);
        }

        final Map<String, Object> content = new HashMap<>();
        content.put("type", "text");
        content.put("text", text.toString());

        final Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", content);

        return Map.of("messages", List.of(message));
    }

    /**
     * Processes document items to convert non-serializable objects (like TextFragment) to strings.
     *
     * @param documentItems The list of document items from search results
     * @return A list of processed document items with serializable values
     */
    @SuppressWarnings("unchecked")
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
     * @param value The value to process
     * @return The processed value (String for TextFragment, recursively processed for collections)
     */
    @SuppressWarnings("unchecked")
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
     * Gets the maximum content length from system property.
     *
     * @return The maximum content length
     */
    protected int getContentMaxLength() {
        return Integer.parseInt(System.getProperty("mcp.content.max.length", String.valueOf(DEFAULT_CONTENT_MAX_LENGTH)));
    }

    /**
     * Truncates content to the specified maximum length.
     *
     * @param content   The content to truncate
     * @param maxLength The maximum length
     * @return The truncated content
     */
    protected String truncateContent(final String content, final int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    /**
     * Creates a document content entry in Markdown format for MCP response.
     *
     * @param doc   The processed document
     * @param index The result index (1-based)
     * @return A map containing type and text for MCP content
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

        final String content = String.valueOf(doc.getOrDefault("content", ""));
        sb.append(truncateContent(content, getContentMaxLength()));

        return Map.of("type", "text", "text", sb.toString());
    }

    /**
     * Writes an error response in JSON-RPC 2.0 format to the provided HTTP response.
     *
     * @param id       The identifier of the request, which can be null if not applicable.
     * @param code     The error code representing the type of error.
     * @param message  A descriptive message providing details about the error.
     * @param response The {@link HttpServletResponse} object to which the error response will be written.
     */
    protected void writeError(final Object id, final ErrorCode code, final String message, final HttpServletResponse response) {
        final Map<String, Object> error = Map.of("code", code.getCode(), "message", message);
        final Map<String, Object> errorResponse = Map.of("jsonrpc", "2.0", "id", id, "error", error);
        try {
            write(JsonXContent.contentBuilder().map(errorResponse).toString(), mimeType, Constants.UTF_8);
        } catch (final IOException e) {
            logger.warn("Failed to write error response", e);
        }
    }
}
