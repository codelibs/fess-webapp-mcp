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
package org.codelibs.fess.plugin.webapp.mcp.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.json.Json;

/**
 * Writes MCP responses straight to the servlet response.
 *
 * <p>This deliberately bypasses {@code BaseApiManager#write}, which cannot set a status,
 * resolves the response from a thread local and closes the stream. It also never calls
 * {@code sendError}: Fess maps 400/401/403/404/408/429/500 to {@code redirect.jsp}, and
 * because {@code /mcp} is not recognised by {@code WebApiUtil#isApiRequestUri} the
 * container would turn the response into a 302 and drop any {@code WWW-Authenticate}
 * challenge.</p>
 */
public class McpResponseWriter {

    private static final Logger logger = LogManager.getLogger(McpResponseWriter.class);

    /** Content type applied to every non-empty response body written by this class. */
    private static final String CONTENT_TYPE = "application/json; charset=UTF-8";

    /** The server name reported in {@code _meta} on every result. */
    private final String serverName;

    /** The server version reported in {@code _meta} on every result. */
    private final String serverVersion;

    /**
     * Creates a writer that stamps the given identity into every result.
     *
     * @param serverName the server name reported in {@code _meta}
     * @param serverVersion the server version reported in {@code _meta}
     */
    public McpResponseWriter(final String serverName, final String serverVersion) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    /**
     * Returns the {@code Implementation} object this server reports.
     *
     * @return a map with {@code name} and {@code version}
     */
    public Map<String, Object> serverInfo() {
        final Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", serverName);
        info.put("version", serverVersion);
        return info;
    }

    /**
     * Writes a successful result, injecting {@code resultType} and {@code _meta.serverInfo}.
     *
     * <p>Both injections use {@code putIfAbsent}: a value the handler already supplied for
     * {@code resultType} or for {@code _meta["io.modelcontextprotocol/serverInfo"]} takes
     * priority and is left untouched, and any other entry already present in {@code _meta}
     * survives alongside the injected {@code serverInfo}.</p>
     *
     * @param response the servlet response
     * @param id the request id
     * @param result the handler's result object; mutated in place. Must be a mutable map, and
     *     if it already contains a {@code _meta} entry that entry must be a mutable map too, since
     *     both are written to directly
     */
    @SuppressWarnings("unchecked")
    public void writeResult(final HttpServletResponse response, final Object id, final Map<String, Object> result) {
        result.putIfAbsent("resultType", McpConstants.RESULT_TYPE_COMPLETE);
        final Object rawMeta = result.get("_meta");
        final Map<String, Object> meta = rawMeta instanceof Map ? (Map<String, Object>) rawMeta : new LinkedHashMap<>();
        meta.putIfAbsent(McpConstants.META_SERVER_INFO, serverInfo());
        result.put("_meta", meta);

        final Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", McpConstants.JSONRPC_VERSION);
        envelope.put("id", id);
        envelope.put("result", result);
        write(response, HttpServletResponse.SC_OK, Json.write(envelope));
    }

    /**
     * Writes a JSON-RPC error response.
     *
     * <p>When {@code hasId} is false the {@code id} member is omitted entirely.
     * {@code RequestId} is {@code string | number}; {@code null} is not part of the type.</p>
     *
     * @param response the servlet response
     * @param id the request id, ignored when {@code hasId} is false
     * @param hasId whether the request carried a usable id
     * @param error the failure to report
     */
    public void writeError(final HttpServletResponse response, final Object id, final boolean hasId, final McpError error) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getErrorCode().getCode());
        body.put("message", error.getMessage());
        if (error.getData() != null) {
            body.put("data", error.getData());
        }

        final Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", McpConstants.JSONRPC_VERSION);
        if (hasId) {
            envelope.put("id", id);
        }
        envelope.put("error", body);
        write(response, error.getHttpStatus(), Json.write(envelope));
    }

    /**
     * Acknowledges an accepted notification with 202 and no body.
     *
     * @param response the servlet response
     */
    public void writeAccepted(final HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
    }

    /**
     * Sets the status and writes the body. Never calls {@code sendError}.
     *
     * @param response the servlet response
     * @param status the HTTP status
     * @param json the response body
     */
    protected void write(final HttpServletResponse response, final int status, final String json) {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE);
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        } catch (final IOException e) {
            logger.warn("[MCP] Failed to write response: error={}", e.getMessage(), e);
        }
    }
}
