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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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

/**
 * The {@code McpApiManager} class is responsible for handling JSON-RPC 2.0 API requests
 * for the MCP (Management Control Protocol) API. It extends the {@code BaseApiManager}
 * and provides methods to process incoming HTTP requests, validate JSON-RPC requests,
 * and dispatch them to the appropriate handlers.
 *
 */
public class McpApiManager extends BaseApiManager {

    private static final Logger logger = LogManager.getLogger(McpApiManager.class);

    protected String mimeType = "application/json";

    public McpApiManager() {
        // JSON-RPC endpoint is /mcp/*
        setPathPrefix("/mcp");
    }

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

    protected Map<String, Object> parseRequestBody(final HttpServletRequest request) throws IOException {
        return JsonXContent.jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, request.getInputStream()).map();
    }

    protected Object dispatchRpcMethod(final String method, final Map<String, Object> params) {
        return switch (method) {
        case "initialize" -> handleInitialize();
        case "listTools" -> handleListTools();
        case "invoke" -> handleInvoke(params);
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
     *         - "version": the version of the API (e.g., "1.0").
     *         - "protocol": the protocol used by the API (e.g., "MCP/1.0").
     *         - "methods": a list of supported RPC methods (e.g., "initialize", "listTools", "invoke").
     */
    protected Map<String, Object> handleInitialize() {
        final Map<String, Object> caps = new HashMap<>();
        caps.put("version", "1.0");
        caps.put("protocol", "MCP/1.0");
        caps.put("methods", Arrays.asList("initialize", "listTools", "invoke"));

        final Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", "fess-mcp-server");
        serverInfo.put("version", "1.0.0");

        return Map.of("capabilities", caps, "serverInfo", serverInfo);
    }

    /**
     * Handles the creation of a list of tools with their metadata.
     *
     * @return A list containing a single map that represents a tool. The map includes:
     *         - "name": The name of the tool (e.g., "search").
     *         - "description": A brief description of the tool (e.g., "Search documents via Fess").
     *         - "parameters": A list of parameter maps, where each parameter map contains:
     *             - "name": The name of the parameter (e.g., "q").
     *             - "type": The type of the parameter (e.g., "string").
     *             - "desc": A description of the parameter (e.g., "query string").
     */
    protected Map<String, Object> handleListTools() {
        final Map<Object, Object> toolSearch = new HashMap<>() {
            {
                put("name", "search"); //
                put("description", "Search documents via Fess"); //
                put("parameters", List.of( //
                        Map.of("name", "q", "type", "string", "desc", "query string"), //
                        Map.of("name", "start", "type", "integer", "desc", "start position"), //
                        Map.of("name", "offset", "type", "integer", "desc", "offset (alias of start)"), //
                        Map.of("name", "num", "type", "integer", "desc", "number of results"), //
                        Map.of("name", "sort", "type", "string", "desc", "sort order"), //
                        Map.of("name", "fields.label", "type", "array", "desc", "labels to return"), //
                        // Map.of("name", "facet.field", "type", "array", "desc", "facet fields"), //
                        // Map.of("name", "facet.query", "type", "array", "desc", "facet queries"), //
                        // Map.of("name", "facet.size", "type", "integer", "desc", "facet size"), //
                        // Map.of("name", "facet.minDocCount", "type", "integer", "desc", "facet minDocCount"), //
                        // Map.of("name", "geo.location.point", "type", "string", "desc", "geo point"), //
                        // Map.of("name", "geo.location.distance", "type", "string", "desc", "geo distance"), //
                        Map.of("name", "lang", "type", "string", "desc", "language"), //
                        Map.of("name", "preference", "type", "string", "desc", "preference") //
                ));
            }
        };
        return Map.of("tools", List.of(toolSearch));
    }

    /**
     * Handles the invocation of the MCP API by processing the input parameters,
     * executing a search query, and formatting the search results.
     *
     * @param params A map containing the input parameters for the API call.
     *               It must include a nested map under the key "params" with a required key "q"
     *               representing the search query string.
     * @return A map containing the search results under the key "documents".
     *         Each document is represented as a map with fields such as "id", "title", "url", and "score".
     * @throws McpApiException If the required parameter "q" is missing or invalid.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> handleInvoke(final Map<String, Object> params) {
        final String tool = (String) params.get("method");
        if (tool == null) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: tool");
        }

        final Map<String, Object> toolParams = (Map<String, Object>) params.get("params");
        return switch (tool) {
        case "search" -> invokeSearch(toolParams);
        // TODO Add administrative tools here...
        default -> throw new McpApiException(ErrorCode.InvalidParams, "Unknown tool: " + tool);
        };
    }

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
                    return fields.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> ((List<String>) e.getValue()).toArray(n -> new String[n])));
                }
                return Collections.emptyMap();
            }

            @Override
            public Map<String, String[]> getConditions() {
                final Map<String, Object> conditions = (Map<String, Object>) paramMap.get("as");
                if (conditions != null) {
                    return conditions.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
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
                return fessConfig.getPagingSearchPageSizeAsInteger();
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
        };

        // Execute search
        final SearchRenderData data = new SearchRenderData();
        ComponentUtil.getSearchHelper().search(reqParams, data, OptionalThing.empty());

        // Build response map
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("q", data.getSearchQuery());
        result.put("query_id", data.getQueryId());
        result.put("exec_time", data.getExecTime());
        result.put("query_time", data.getQueryTime());
        result.put("page_size", data.getPageSize());
        result.put("page_number", data.getCurrentPageNumber());
        result.put("record_count", data.getAllRecordCount());
        result.put("record_count_relation", data.getAllRecordCountRelation());
        result.put("page_count", data.getAllPageCount());
        result.put("next_page", data.isExistNextPage());
        result.put("prev_page", data.isExistPrevPage());
        result.put("data", data.getDocumentItems());

        // Add facet information if available
        if (data.getFacetResponse() != null && data.getFacetResponse().hasFacetResponse()) {
            result.put("facet_field", data.getFacetResponse().getFieldList().stream().map(field -> {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", field.getName());
                m.put("result",
                        field.getValueCountMap().entrySet().stream().map(e -> Map.of("value", e.getKey(), "count", e.getValue())).toList());
                return m;
            }).toList());
            result.put("facet_query", data.getFacetResponse().getQueryCountMap().entrySet().stream()
                    .map(e -> Map.of("value", e.getKey(), "count", e.getValue())).toList());
        }

        return result;
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
        final StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"jsonrpc\":\"2.0\",");
        sb.append("\"id\":").append(id == null ? "null" : "\"" + id + "\"").append(",");
        sb.append("\"error\":{");
        sb.append("\"code\":").append(code.getCode()).append(",");
        sb.append("\"message\":\"").append(message).append("\"");
        sb.append("}");
        sb.append("}");
        write(sb.toString(), mimeType, Constants.UTF_8);
    }
}
