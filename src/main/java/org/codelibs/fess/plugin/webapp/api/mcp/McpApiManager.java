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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.Constants;
import org.codelibs.fess.api.BaseApiManager;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.tool.GetDocumentTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.IndexStatsTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.SearchTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.SuggestTool;
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

    /** Static sort candidate values for advanced_search.sort completion. */
    protected static final List<String> SORT_VALUES =
            List.of("score.desc", "score.asc", "last_modified.desc", "last_modified.asc", "create_timestamp.desc", "create_timestamp.asc");

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
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            response.setHeader("Allow", "POST");
            return;
        }
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
     *
     * @param requestBody the raw JSON-RPC request body
     * @param response    the HTTP servlet response to write the result to
     * @throws IOException if writing the response fails
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
     *
     * @param requestBody the raw JSON-RPC batch request body (a JSON array)
     * @param response    the HTTP servlet response to write the batch result to
     * @throws IOException if writing the response fails
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
        final List<Map<String, Object>> responses = new ArrayList<>();
        for (final Object item : rawList) {
            if (item instanceof Map) {
                requests.add((Map<String, Object>) item);
            } else {
                // Non-object items in batch should produce InvalidRequest error per JSON-RPC 2.0
                final Map<String, Object> errorResponse = new LinkedHashMap<>();
                errorResponse.put("jsonrpc", "2.0");
                errorResponse.put("id", null);
                errorResponse.put("error",
                        Map.of("code", ErrorCode.InvalidRequest.getCode(), "message", "Invalid request object in batch"));
                responses.add(errorResponse);
            }
        }

        responses.addAll(processBatchRequests(requests));

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
        final Map<String, Object> error = Map.of("code", code.getCode(), "message", message != null ? message : "Unknown error");
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
            throw new McpApiException(ErrorCode.ParseError, "Empty request body");
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
        // "initialize" and "ping" are gone in MCP 2026-07-28 (see McpDispatcher and
        // org.codelibs.fess.plugin.webapp.mcp.handler): neither is a case here any more, so both
        // fall through to the default MethodNotFound branch below, same as any other unknown
        // method name.
        return switch (method) {
        case "tools/list" -> handleListTools(params);
        case "tools/call" -> handleInvoke(params);
        case "resources/list" -> handleListResources(params);
        case "resources/read" -> handleReadResource(params);
        case "resources/templates/list" -> handleListResourceTemplates(params);
        case "prompts/list" -> handleListPrompts(params);
        case "prompts/get" -> handleGetPrompt(params);
        case "completion/complete" -> handleComplete(params);
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
        final List<Map<String, Object>> tools = getTools().stream().map(this::describeTool).collect(Collectors.toList());
        return Map.of("tools", tools);
    }

    /**
     * Builds the {@code tools/list} descriptor for one tool.
     * <p>
     * {@code outputSchema} is deliberately not included here: {@link McpTool#getOutputSchema()}
     * exists so a later task can populate {@code structuredContent}, but until that task wires
     * {@code structuredContent} into tool results, advertising an {@code outputSchema} would
     * promise MCP clients a contract this server does not yet honour.
     * </p>
     *
     * @param tool the tool to describe
     * @return a map with {@code name}, {@code description}, {@code inputSchema}, and
     *         {@code annotations}
     */
    protected Map<String, Object> describeTool(final McpTool tool) {
        final Map<String, Object> descriptor = new HashMap<>();
        descriptor.put("name", tool.getName());
        descriptor.put("description", tool.getDescription());
        descriptor.put("inputSchema", tool.getInputSchema());
        descriptor.put("annotations", tool.getAnnotations());
        return descriptor;
    }

    /**
     * Returns the MCP tools this server exposes, in {@code tools/list} order.
     *
     * @return the tool list
     */
    protected List<McpTool> getTools() {
        return List.of(new SearchTool(), new IndexStatsTool(), new SuggestTool(), new GetDocumentTool());
    }

    /**
     * Finds a registered tool by name.
     *
     * @param name the tool name
     * @return the matching tool, or {@code null} when no registered tool has that name
     */
    protected McpTool findTool(final String name) {
        for (final McpTool tool : getTools()) {
            if (tool.getName().equals(name)) {
                return tool;
            }
        }
        return null;
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

        final McpTool mcpTool = findTool(tool);
        if (mcpTool == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("[MCP] Unknown tool requested: {}", tool);
            }
            throw new McpApiException(ErrorCode.InvalidParams, "Unknown tool: " + tool);
        }

        try {
            return mcpTool.call(toolParams, new McpCallContext());
        } catch (final McpApiException e) {
            throw e;
        } catch (final Exception e) {
            logger.warn("[MCP] Tool '{}' execution failed: {}", tool, e.getMessage(), e);
            final String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", "Error: " + errorMessage)));
            result.put("isError", true);
            return result;
        }
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
            throw new McpApiException(ErrorCode.InvalidParams, "Unknown resource: " + uri);
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
        }).orElseThrow(() -> new McpApiException(ErrorCode.InvalidParams, "Document not found: " + docId));
    }

    /**
     * Builds the index stats resource content.
     *
     * @return A map with "contents" key containing the index stats
     */
    protected Map<String, Object> buildIndexStatsResource() {
        try {
            final Map<String, Object> stats = new IndexStatsTool().collectIndexStats();
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
     * @return the MCP completion/complete response map with {@code values}, {@code total}, and {@code hasMore}
     */
    protected Map<String, Object> completeViaSuggest(final String query) {
        final org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder builder =
                ComponentUtil.getSuggestHelper().suggester().suggest();
        builder.setQuery(query);
        builder.setSize(100);
        builder.addKind(org.codelibs.fess.suggest.entity.SuggestItem.Kind.QUERY.toString());
        builder.addKind(org.codelibs.fess.suggest.entity.SuggestItem.Kind.DOCUMENT.toString());

        final org.codelibs.fess.suggest.request.suggest.SuggestResponse suggestResponse = builder.execute().getResponse();

        final List<String> values = new java.util.ArrayList<>();
        if (suggestResponse.getItems() != null) {
            for (final org.codelibs.fess.suggest.entity.SuggestItem item : suggestResponse.getItems()) {
                values.add(item.getText());
            }
        }

        // Cap returned values at 100 per MCP completion spec.
        final List<String> capped = values.size() > 100 ? values.subList(0, 100) : values;
        final int total = (int) suggestResponse.getTotal();
        final boolean hasMore = total > capped.size();
        return buildCompletionResult(capped, total, hasMore);
    }

    /**
     * Builds the MCP completion/complete response envelope.
     *
     * @param values  the candidate completion values (capped at 100)
     * @param total   the total number of candidates known to the server
     * @param hasMore whether more candidates exist beyond the returned values
     * @return the MCP completion/complete response map with {@code completion.values}, {@code completion.total}, and {@code completion.hasMore}
     */
    protected Map<String, Object> buildCompletionResult(final List<String> values, final int total, final boolean hasMore) {
        // Cap at 100 per MCP spec.
        final List<String> capped = values.size() > 100 ? values.subList(0, 100) : values;
        final int reportedTotal = Math.max(total, capped.size());
        final boolean reportedHasMore = hasMore || reportedTotal > capped.size();
        final Map<String, Object> completion = new java.util.LinkedHashMap<>();
        completion.put("values", capped);
        completion.put("total", reportedTotal);
        completion.put("hasMore", reportedHasMore);
        return Map.of("completion", completion);
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
        final Map<String, Object> error = Map.of("code", code.getCode(), "message", message != null ? message : "Unknown error");
        final Map<String, Object> errorResponse = new LinkedHashMap<>();
        errorResponse.put("jsonrpc", "2.0");
        errorResponse.put("id", id);
        errorResponse.put("error", error);
        try {
            write(JsonXContent.contentBuilder().map(errorResponse).toString(), mimeType, Constants.UTF_8);
        } catch (final IOException e) {
            logger.warn("Failed to write error response", e);
        }
    }
}
