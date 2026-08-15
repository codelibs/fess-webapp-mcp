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

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;

/**
 * A parsed JSON-RPC envelope. Distinguishes an absent {@code id} key (a notification)
 * from an explicit {@code "id": null} (an invalid Request object in MCP 2026-07-28).
 */
public class McpRequest {

    private final boolean hasId;
    private final Object id;
    private final String method;
    private final Map<String, Object> params;

    private McpRequest(final boolean hasId, final Object id, final String method, final Map<String, Object> params) {
        this.hasId = hasId;
        this.id = id;
        this.method = method;
        this.params = params;
    }

    /**
     * Parses and validates a JSON-RPC envelope.
     *
     * @param map the decoded JSON object
     * @return the parsed request
     * @throws McpError if the envelope is not a valid Request object
     */
    @SuppressWarnings("unchecked")
    public static McpRequest parse(final Map<String, Object> map) {
        if (!McpConstants.JSONRPC_VERSION.equals(map.get("jsonrpc"))) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.InvalidRequest, "jsonrpc must be \"2.0\"");
        }
        final Object method = map.get("method");
        if (!(method instanceof String) || ((String) method).isEmpty()) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.InvalidRequest, "method is required");
        }
        final boolean hasId = map.containsKey("id");
        final Object id = map.get("id");
        if (hasId && id == null) {
            // MCP 2026-07-28: "Unlike base JSON-RPC, the ID MUST NOT be null."
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.InvalidRequest, "id must not be null");
        }
        if (hasId && !(id instanceof String) && !(id instanceof Number)) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.InvalidRequest, "id must be a string or a number");
        }
        final Object rawParams = map.get("params");
        final Map<String, Object> params =
                rawParams instanceof Map ? (Map<String, Object>) rawParams : Collections.<String, Object> emptyMap();
        return new McpRequest(hasId, id, (String) method, params);
    }

    /**
     * Returns whether the envelope carried an {@code id} key.
     *
     * @return true when an id was present
     */
    public boolean hasId() {
        return hasId;
    }

    /**
     * Returns the request id.
     *
     * @return the id, or null when this is a notification
     */
    public Object getId() {
        return id;
    }

    /**
     * Returns the JSON-RPC method name.
     *
     * @return the method name
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the params object, never null.
     *
     * @return the params map
     */
    public Map<String, Object> getParams() {
        return params;
    }

    /**
     * Returns whether this message is a notification (no id).
     *
     * @return true when this is a notification
     */
    public boolean isNotification() {
        return !hasId;
    }
}
