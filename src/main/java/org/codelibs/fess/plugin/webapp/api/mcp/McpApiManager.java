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
package org.codelibs.fess.plugin.webapp.api.mcp;

import java.io.IOException;
import java.util.ArrayList;
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

    private static final java.util.Set<String> VALID_LOG_LEVELS =
            java.util.Set.of("debug", "info", "notice", "warning", "error", "critical", "alert", "emergency");

    private volatile String mcpLogLevel = "warning";

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
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Incoming request: {} {} Content-Type={} RemoteAddr={}", request.getMethod(), request.getRequestURI(),
                        request.getContentType(), request.getRemoteAddr());
            }

            final String requestBody = readRequestBody(request);
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Raw request body: {}", requestBody);
            }

            final String trimmed = requestBody.trim();
            if (trimmed.startsWith("[")) {
                processBatchRequest(trimmed, response);
            } else {
                processSingleRequest(trimmed, response);
            }
        } catch (final Exception e) {
            logger.warn("[MCP] Unexpected error reading request body: error={}", e.getMessage(), e);
            writeError(null, ErrorCode.ParseError, e.getMessage(), response);
        }
    }

    /**
     * Reads the raw request body from the HTTP request.
     *
     * @param request the HTTP servlet request
     * @return the request body as a string
     * @throws IOException if an I/O error occurs while reading the request
     */
    protected String readRequestBody(final HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), Constants.UTF_8);
    }

    /**
     * Processes a single JSON-RPC request.
     */
    protected void processSingleRequest(final String requestBody, final HttpServletResponse response) throws IOException {
        Object rpcId = null;
        String method = null;
        Map<String, Object> params = Collections.emptyMap();
        try {
            final Map<String, Object> reqMap = parseJsonObject(requestBody);
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Parsed request body: {}", reqMap);
            }

            // Retrieve JSON-RPC fields
            final String jsonrpc = (String) reqMap.get("jsonrpc");
            method = (String) reqMap.get("method");
            rpcId = reqMap.get("id");
            @SuppressWarnings("unchecked")
            final Map<String, Object> paramsMap =
                    Optional.ofNullable((Map<String, Object>) reqMap.get("params")).orElse(Collections.emptyMap());
            params = paramsMap;
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] JSON-RPC fields: jsonrpc={}, method={}, id={}, params={}", jsonrpc, method, rpcId, params);
            }

            // Validate the request
            if (!"2.0".equals(jsonrpc) || method == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("[MCP] Validation failed: jsonrpc='{}' (expected '2.0'), method={}", jsonrpc, method);
                }
                throw new McpApiException(ErrorCode.InvalidRequest, "Invalid JSON-RPC request: jsonrpc=" + jsonrpc + ", method=" + method);
            }

            // JSON-RPC 2.0: requests without "id" are notifications and MUST NOT receive a response
            if (rpcId == null) {
                dispatchNotification(method, params);
                if (logger.isDebugEnabled()) {
                    logger.debug("[MCP] Notification '{}' processed (no response sent)", method);
                }
                return;
            }

            // Execute the method
            final Object result = dispatchRpcMethod(method, params);
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Method '{}' completed successfully", method);
            }

            final Map<String, Object> resMap = new LinkedHashMap<>();
            resMap.put("jsonrpc", "2.0");
            resMap.put("id", rpcId);
            resMap.put("result", result);
            write(JsonXContent.contentBuilder().map(resMap).toString(), mimeType, Constants.UTF_8);
        } catch (final McpApiException mae) {
            // Client error - log at debug level
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Client error: code={}, message='{}', id={}, method={}, params={}", mae.getCode(), mae.getMessage(),
                        rpcId, method, params);
            }
            if (rpcId != null) {
                writeError(rpcId, mae.getCode(), mae.getMessage(), response);
            }
        } catch (final Exception e) {
            // Unexpected error - log at warn level (potential system issue)
            logger.warn("[MCP] Unexpected error processing request: id={}, method={}, params={}, error={}", rpcId, method, params,
                    e.getMessage(), e);
            if (rpcId != null) {
                writeError(rpcId, ErrorCode.InternalError, e.getMessage(), response);
            }
        }
    }

    /**
     * Processes a batch JSON-RPC request (JSON array of requests).
     * Per JSON-RPC 2.0 specification, batch requests MUST be supported.
     */
    @SuppressWarnings("unchecked")
    protected void processBatchRequest(final String requestBody, final HttpServletResponse response) throws IOException {
        final List<Object> rawList;
        try {
            rawList = JsonXContent.jsonXContent.createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, requestBody)
                    .list();
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Failed to parse batch request body as JSON array: error='{}'", e.getMessage());
            }
            writeError(null, ErrorCode.ParseError, "Failed to parse batch request: " + e.getMessage(), response);
            return;
        }

        if (rawList.isEmpty()) {
            writeError(null, ErrorCode.InvalidRequest, "Batch request must not be empty", response);
            return;
        }

        final List<Map<String, Object>> requests = new ArrayList<>();
        for (final Object item : rawList) {
            if (item instanceof Map) {
                requests.add((Map<String, Object>) item);
            }
        }

        final List<Map<String, Object>> responses = processBatchRequests(requests);

        if (responses.isEmpty()) {
            // All were notifications - no response per JSON-RPC 2.0 spec
            return;
        }

        final StringBuilder batchJson = new StringBuilder("[");
        for (int i = 0; i < responses.size(); i++) {
            if (i > 0) {
                batchJson.append(",");
            }
            batchJson.append(JsonXContent.contentBuilder().map(responses.get(i)).toString());
        }
        batchJson.append("]");
        write(batchJson.toString(), mimeType, Constants.UTF_8);
    }

    /**
     * Processes a list of JSON-RPC requests and returns a list of responses.
     * Notifications (requests without id) do not produce responses.
     *
     * @param requests the list of parsed JSON-RPC request maps
     * @return the list of response maps
     */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> processBatchRequests(final List<Map<String, Object>> requests) {
        final List<Map<String, Object>> responses = new ArrayList<>();
        for (final Map<String, Object> reqMap : requests) {
            final String jsonrpc = (String) reqMap.get("jsonrpc");
            final String method = (String) reqMap.get("method");
            final Object rpcId = reqMap.get("id");
            final Map<String, Object> params =
                    Optional.ofNullable((Map<String, Object>) reqMap.get("params")).orElse(Collections.emptyMap());

            if (!"2.0".equals(jsonrpc) || method == null) {
                if (rpcId != null) {
                    responses.add(createErrorResponse(rpcId, ErrorCode.InvalidRequest,
                            "Invalid JSON-RPC request: jsonrpc=" + jsonrpc + ", method=" + method));
                }
                continue;
            }

            // Notifications (no id) do not produce responses
            if (rpcId == null) {
                dispatchNotification(method, params);
                continue;
            }

            try {
                final Object result = dispatchRpcMethod(method, params);
                final Map<String, Object> resMap = new LinkedHashMap<>();
                resMap.put("jsonrpc", "2.0");
                resMap.put("id", rpcId);
                resMap.put("result", result);
                responses.add(resMap);
            } catch (final McpApiException mae) {
                responses.add(createErrorResponse(rpcId, mae.getCode(), mae.getMessage()));
            } catch (final Exception e) {
                logger.warn("[MCP] Batch request error: id={}, method={}, error={}", rpcId, method, e.getMessage(), e);
                responses.add(createErrorResponse(rpcId, ErrorCode.InternalError, e.getMessage()));
            }
        }
        return responses;
    }

    /**
     * Creates a JSON-RPC 2.0 error response map.
     *
     * @param id the request id
     * @param code the error code
     * @param message the error message
     * @return the error response map
     */
    protected Map<String, Object> createErrorResponse(final Object id, final ErrorCode code, final String message) {
        final Map<String, Object> error = Map.of("code", code.getCode(), "message", message);
        final Map<String, Object> errorResponse = new LinkedHashMap<>();
        errorResponse.put("jsonrpc", "2.0");
        errorResponse.put("id", id);
        errorResponse.put("error", error);
        return errorResponse;
    }

    /**
     * Parses a JSON string as a map (JSON object).
     *
     * @param requestBody the JSON string to parse
     * @return a map containing the parsed JSON
     * @throws IOException if parsing fails
     */
    protected Map<String, Object> parseJsonObject(final String requestBody) throws IOException {
        if (requestBody == null || requestBody.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Request body is empty");
            }
            return Collections.emptyMap();
        }
        try {
            return JsonXContent.jsonXContent.createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, requestBody)
                    .map();
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Failed to parse request body as JSON: body='{}', error='{}'", requestBody, e.getMessage());
            }
            throw e;
        }
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
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Dispatching method: {}", method);
        }
        return switch (method) {
        case "initialize" -> handleInitialize();
        case "ping" -> handlePing();
        case "tools/list" -> handleListTools(params);
        case "tools/call" -> handleInvoke(params);
        case "resources/list" -> handleListResources(params);
        case "resources/read" -> handleReadResource(params);
        case "resources/templates/list" -> handleListResourceTemplates(params);
        case "prompts/list" -> handleListPrompts(params);
        case "prompts/get" -> handleGetPrompt(params);
        case "completion/complete" -> handleComplete(params);
        case "logging/setLevel" -> handleSetLogLevel(params);
        default -> {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Unknown method requested: {}", method);
            }
            throw new McpApiException(ErrorCode.MethodNotFound, "Unknown method: " + method);
        }
        };
    }

    /**
     * Dispatches a JSON-RPC notification (request without id).
     * Notifications MUST NOT produce a response per JSON-RPC 2.0 specification.
     *
     * @param method the notification method name
     * @param params the notification parameters
     */
    protected void dispatchNotification(final String method, final Map<String, Object> params) {
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Dispatching notification: {}", method);
        }
        switch (method) {
        case "notifications/initialized":
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Client initialized notification received");
            }
            break;
        case "notifications/cancelled":
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Cancellation notification received: {}", params);
            }
            break;
        default:
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Unknown notification received: {}", method);
            }
            break;
        }
    }

    @Override
    protected void writeHeaders(final HttpServletResponse response) {
        ComponentUtil.getFessConfig().getApiJsonResponseHeaderList().forEach(e -> response.setHeader(e.getFirst(), e.getSecond()));
    }

    /**
     * Handles the ping request per MCP specification.
     * Returns an empty result to indicate the server is alive.
     *
     * @return an empty map
     */
    protected Map<String, Object> handlePing() {
        return Collections.emptyMap();
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
        caps.put("logging", new HashMap<>());
        caps.put("completions", new HashMap<>());

        final Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", "fess-mcp-server");
        serverInfo.put("version", "1.0.0");

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", caps);
        result.put("serverInfo", serverInfo);
        result.put("instructions",
                "Fess Enterprise Search Server. Use the 'search' tool to perform full-text search with Lucene-like query syntax "
                        + "(AND default, OR explicit, quotes for phrase, - for exclusion). "
                        + "Use 'get_index_stats' to check index health. Use 'suggest' for query autocomplete.");
        return result;
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
        return handleListTools(Collections.emptyMap());
    }

    /**
     * Handles the creation of a list of tools with their metadata.
     * The cursor param is accepted gracefully but ignored since item counts are small.
     *
     * @param params the request parameters (cursor param accepted but not required)
     * @return A map with "tools" key containing a list of available tools. Each tool includes:
     *         - "name": The name of the tool (e.g., "search").
     *         - "description": A brief description of the tool (e.g., "Search documents via Fess").
     *         - "inputSchema": A JSON Schema object defining the tool's input parameters.
     */
    protected Map<String, Object> handleListTools(final Map<String, Object> params) {
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
        toolSearch.put("annotations",
                Map.of("title", "Search Documents", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));

        // Index stats tool
        final Map<String, Object> statsInputSchema = new HashMap<>();
        statsInputSchema.put("type", "object");
        statsInputSchema.put("properties", new HashMap<>());

        final Map<String, Object> toolStats = new HashMap<>();
        toolStats.put("name", "get_index_stats");
        toolStats.put("description", "Get index statistics and information");
        toolStats.put("inputSchema", statsInputSchema);
        toolStats.put("annotations",
                Map.of("title", "Get Index Statistics", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));

        // Suggest tool
        final Map<String, Object> suggestProperties = new HashMap<>();
        suggestProperties.put("q", Map.of("type", "string", "description", "query prefix for autocomplete"));
        suggestProperties.put("num", Map.of("type", "integer", "description", "number of suggestions", "default", 10));

        final Map<String, Object> suggestInputSchema = new HashMap<>();
        suggestInputSchema.put("type", "object");
        suggestInputSchema.put("properties", suggestProperties);
        suggestInputSchema.put("required", List.of("q"));

        final Map<String, Object> toolSuggest = new HashMap<>();
        toolSuggest.put("name", "suggest");
        toolSuggest.put("description", "Get autocomplete suggestions for a search query prefix");
        toolSuggest.put("inputSchema", suggestInputSchema);
        toolSuggest.put("annotations", Map.of("title", "Suggest", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));

        // Get document tool
        final Map<String, Object> getDocProperties = new HashMap<>();
        getDocProperties.put("doc_id", Map.of("type", "string", "description", "document ID to retrieve"));

        final Map<String, Object> getDocInputSchema = new HashMap<>();
        getDocInputSchema.put("type", "object");
        getDocInputSchema.put("properties", getDocProperties);
        getDocInputSchema.put("required", List.of("doc_id"));

        final Map<String, Object> toolGetDoc = new HashMap<>();
        toolGetDoc.put("name", "get_document");
        toolGetDoc.put("description", "Retrieve a document by its document ID");
        toolGetDoc.put("inputSchema", getDocInputSchema);
        toolGetDoc.put("annotations",
                Map.of("title", "Get Document", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));

        return Map.of("tools", List.of(toolSearch, toolStats, toolSuggest, toolGetDoc));
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

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Invoking tool: name={}, arguments={}", tool, toolParams);
        }

        try {
            return switch (tool) {
            case "search" -> invokeSearch(toolParams);
            case "get_index_stats" -> invokeGetIndexStats();
            case "suggest" -> invokeSuggest(toolParams);
            case "get_document" -> invokeGetDocument(toolParams);
            // TODO Add more administrative tools here...
            default -> {
                if (logger.isDebugEnabled()) {
                    logger.debug("[MCP] Unknown tool requested: {}", tool);
                }
                throw new McpApiException(ErrorCode.InvalidParams, "Unknown tool: " + tool);
            }
            };
        } catch (final McpApiException e) {
            throw e;
        } catch (final Exception e) {
            logger.warn("[MCP] Tool '{}' execution failed: {}", tool, e.getMessage(), e);
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())));
            result.put("isError", true);
            return result;
        }
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
                final int fragmentSize = fessConfig.getSystemPropertyAsInt("mcp.highlight.fragment.size", 500);
                final int numOfFragments = fessConfig.getSystemPropertyAsInt("mcp.highlight.num.of.fragments", 3);
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
                return fessConfig.getSystemPropertyAsInt("mcp.default.page.size", 3);
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
                return new String[] { fessConfig.getIndexFieldTitle(), fessConfig.getIndexFieldContent(), fessConfig.getIndexFieldUrl(),
                        fessConfig.getResponseFieldContentDescription() };
            }
        };

        // Execute search
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Executing search: query='{}', start={}, num={}, sort={}", reqParams.getQuery(),
                    reqParams.getStartPosition(), reqParams.getPageSize(), reqParams.getSort());
        }
        final SearchRenderData data = new SearchRenderData();
        ComponentUtil.getSearchHelper().search(reqParams, data, OptionalThing.empty());
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Search completed: resultCount={}", data.getDocumentItems() != null ? data.getDocumentItems().size() : 0);
        }

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
            return Map.of("content", List.of(content));
        } catch (final IOException e) {
            throw new McpApiException(ErrorCode.InternalError, "Failed to serialize index stats: " + e.getMessage());
        }
    }

    /**
     * Invokes the suggest tool to provide query autocomplete suggestions.
     *
     * @param params the parameters including query prefix (q) and number of suggestions (num)
     * @return a map containing the suggestions in MCP-compliant format
     */
    protected Map<String, Object> invokeSuggest(final Map<String, Object> params) {
        final String query = (String) params.get("q");
        if (query == null || query.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: q");
        }

        final int num = params.get("num") instanceof final Number n ? n.intValue() : 10;

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Executing suggest: query='{}', num={}", query, num);
        }

        final org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder builder =
                ComponentUtil.getSuggestHelper().suggester().suggest();
        builder.setQuery(query);
        builder.setSize(num);
        builder.addKind(org.codelibs.fess.suggest.entity.SuggestItem.Kind.QUERY.toString());
        builder.addKind(org.codelibs.fess.suggest.entity.SuggestItem.Kind.DOCUMENT.toString());

        final org.codelibs.fess.suggest.request.suggest.SuggestResponse suggestResponse = builder.execute().getResponse();

        final List<Map<String, Object>> contents = new java.util.ArrayList<>();
        if (suggestResponse.getItems() != null) {
            for (final org.codelibs.fess.suggest.entity.SuggestItem item : suggestResponse.getItems()) {
                contents.add(Map.of("type", "text", "text", item.getText()));
            }
        }

        if (contents.isEmpty()) {
            contents.add(Map.of("type", "text", "text", "No suggestions found for: " + query));
        }

        return Map.of("content", contents);
    }

    /**
     * Invokes the get_document tool to retrieve a single document by its doc_id.
     *
     * @param params the parameters including doc_id
     * @return a map containing the document content in MCP-compliant format
     */
    protected Map<String, Object> invokeGetDocument(final Map<String, Object> params) {
        final String docId = (String) params.get("doc_id");
        if (docId == null || docId.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: doc_id");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Retrieving document: doc_id={}", docId);
        }

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final String[] fields = new String[] { fessConfig.getIndexFieldTitle(), fessConfig.getIndexFieldContent(),
                fessConfig.getIndexFieldUrl(), fessConfig.getIndexFieldDocId(), fessConfig.getIndexFieldLastModified() };

        return ComponentUtil.getSearchHelper().getDocumentByDocId(docId, fields, OptionalThing.empty()).map(doc -> {
            final String title = String.valueOf(doc.getOrDefault(fessConfig.getIndexFieldTitle(), ""));
            final String url = String.valueOf(doc.getOrDefault(fessConfig.getIndexFieldUrl(), ""));
            final String content = String.valueOf(doc.getOrDefault(fessConfig.getIndexFieldContent(), ""));
            final String displayContent = truncateContent(content, getContentMaxLength());

            final StringBuilder sb = new StringBuilder();
            sb.append("**Title**: ").append(title).append("\n");
            sb.append("**URL**: ").append(url).append("\n");
            sb.append("**Doc ID**: ").append(docId).append("\n\n");
            sb.append(displayContent);

            return Map.<String, Object> of("content", List.of(Map.of("type", "text", "text", sb.toString())));
        }).orElseGet(() -> {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", "Document not found: " + docId)));
            result.put("isError", true);
            return result;
        });
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
        return handleListResources(Collections.emptyMap());
    }

    /**
     * Handles the resources/list request and returns available resources.
     * The cursor param is accepted gracefully but ignored since item counts are small.
     *
     * @param params the request parameters (cursor param accepted but not required)
     * @return A map with "resources" key containing a list of available resources.
     */
    protected Map<String, Object> handleListResources(final Map<String, Object> params) {
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

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Reading resource: uri={}", uri);
        }

        if (uri.startsWith("fess://document/")) {
            final String docId = uri.substring("fess://document/".length());
            return buildDocumentResource(docId);
        }

        return switch (uri) {
        case "fess://index/stats" -> buildIndexStatsResource();
        default -> {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Unknown resource requested: {}", uri);
            }
            throw new McpApiException(ErrorCode.ResourceNotFound, "Unknown resource: " + uri);
        }
        };
    }

    /**
     * Handles the resources/templates/list request and returns available resource templates.
     *
     * @param params the request parameters
     * @return A map with "resourceTemplates" key containing a list of resource templates.
     */
    protected Map<String, Object> handleListResourceTemplates(final Map<String, Object> params) {
        final Map<String, Object> docTemplate = new HashMap<>();
        docTemplate.put("uriTemplate", "fess://document/{doc_id}");
        docTemplate.put("name", "Document by ID");
        docTemplate.put("description", "Retrieve a Fess document by its document ID");
        docTemplate.put("mimeType", "application/json");

        return Map.of("resourceTemplates", List.of(docTemplate));
    }

    /**
     * Builds a document resource by fetching the document with the given ID.
     *
     * @param docId the document ID
     * @return A map with "contents" key containing the document content
     * @throws McpApiException if the document ID is empty or document is not found
     */
    protected Map<String, Object> buildDocumentResource(final String docId) {
        if (docId.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Document ID is empty");
        }

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final String[] fields = new String[] { fessConfig.getIndexFieldTitle(), fessConfig.getIndexFieldContent(),
                fessConfig.getIndexFieldUrl(), fessConfig.getIndexFieldDocId() };

        return ComponentUtil.getSearchHelper().getDocumentByDocId(docId, fields, OptionalThing.empty()).map(doc -> {
            try {
                final String jsonResult = JsonXContent.contentBuilder().map(doc).toString();
                final Map<String, Object> content = new HashMap<>();
                content.put("uri", "fess://document/" + docId);
                content.put("mimeType", "application/json");
                content.put("text", jsonResult);
                return Map.<String, Object> of("contents", List.of(content));
            } catch (final IOException e) {
                throw new McpApiException(ErrorCode.InternalError, "Failed to serialize document: " + e.getMessage());
            }
        }).orElseThrow(() -> new McpApiException(ErrorCode.ResourceNotFound, "Document not found: " + docId));
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
        return handleListPrompts(Collections.emptyMap());
    }

    /**
     * Handles the prompts/list request and returns available prompts.
     * The cursor param is accepted gracefully but ignored since item counts are small.
     *
     * @param params the request parameters (cursor param accepted but not required)
     * @return A map with "prompts" key containing a list of available prompts.
     */
    protected Map<String, Object> handleListPrompts(final Map<String, Object> params) {
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

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Getting prompt: name={}, arguments={}", name, arguments);
        }

        return switch (name) {
        case "basic_search" -> buildBasicSearchPrompt(arguments);
        case "advanced_search" -> buildAdvancedSearchPrompt(arguments);
        default -> {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Unknown prompt requested: {}", name);
            }
            throw new McpApiException(ErrorCode.InvalidParams, "Unknown prompt: " + name);
        }
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
     * Handles the completion/complete request by using Fess suggest to provide autocomplete
     * for prompt arguments.
     *
     * @param params the request parameters including "ref" and "argument"
     * @return a map containing "completion" with "values", "total", and "hasMore"
     * @throws McpApiException if required parameters are missing
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> handleComplete(final Map<String, Object> params) {
        final Map<String, Object> ref = (Map<String, Object>) params.get("ref");
        if (ref == null) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: ref");
        }

        final Map<String, Object> argument = (Map<String, Object>) params.get("argument");
        if (argument == null) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: argument");
        }

        final String argValue = (String) argument.get("value");
        if (argValue == null || argValue.isEmpty()) {
            return Map.of("completion", Map.of("values", List.of(), "hasMore", false));
        }

        // Use Fess suggest for autocomplete
        final org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder builder =
                ComponentUtil.getSuggestHelper().suggester().suggest();
        builder.setQuery(argValue);
        builder.setSize(10);
        builder.addKind(org.codelibs.fess.suggest.entity.SuggestItem.Kind.QUERY.toString());
        builder.addKind(org.codelibs.fess.suggest.entity.SuggestItem.Kind.DOCUMENT.toString());

        final org.codelibs.fess.suggest.request.suggest.SuggestResponse suggestResponse = builder.execute().getResponse();

        final List<String> values = new java.util.ArrayList<>();
        if (suggestResponse.getItems() != null) {
            for (final org.codelibs.fess.suggest.entity.SuggestItem item : suggestResponse.getItems()) {
                values.add(item.getText());
            }
        }

        final boolean hasMore = suggestResponse.getTotal() > values.size();
        final Map<String, Object> completion = new java.util.LinkedHashMap<>();
        completion.put("values", values);
        completion.put("total", (int) suggestResponse.getTotal());
        completion.put("hasMore", hasMore);
        return Map.of("completion", completion);
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
        return ComponentUtil.getFessConfig().getSystemPropertyAsInt("mcp.content.max.length", DEFAULT_CONTENT_MAX_LENGTH);
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
     * Removes HTML highlight tags from the given text.
     * Strips both &lt;em&gt; and &lt;strong&gt; tags commonly used for search highlighting.
     *
     * @param text The text containing HTML highlight tags
     * @return The text with highlight tags removed, or empty string if text is null
     */
    protected String stripHighlightTags(final String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("</?(?:em|strong)>", "");
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

        // Use content_description (highlighted text) if available, fallback to content
        final String contentDescription = String.valueOf(doc.getOrDefault("content_description", ""));
        final String displayContent;
        if (contentDescription.isEmpty() || "null".equals(contentDescription)) {
            // Fallback to raw content with truncation
            final String content = String.valueOf(doc.getOrDefault("content", ""));
            displayContent = truncateContent(content, getContentMaxLength());
        } else {
            // Use highlighted content with tags stripped
            displayContent = stripHighlightTags(contentDescription);
        }
        sb.append(displayContent);

        return Map.of("type", "text", "text", sb.toString());
    }

    /**
     * Handles the logging/setLevel request per MCP specification.
     * Sets the MCP log level after validating against RFC 5424 levels.
     *
     * @param params the method parameters containing the "level" field
     * @return an empty map on success
     * @throws McpApiException if the level is missing or invalid
     */
    protected Map<String, Object> handleSetLogLevel(final Map<String, Object> params) {
        final String level = (String) params.get("level");
        if (level == null || level.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: level");
        }
        if (!VALID_LOG_LEVELS.contains(level)) {
            throw new McpApiException(ErrorCode.InvalidParams, "Invalid log level: " + level + ". Valid levels: " + VALID_LOG_LEVELS);
        }

        mcpLogLevel = level;

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Log level set to: {}", level);
        }

        return Collections.emptyMap();
    }

    /**
     * Returns the current MCP log level.
     *
     * @return the current log level string
     */
    protected String getMcpLogLevel() {
        return mcpLogLevel;
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
