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
 * The {@code params._meta} object every MCP 2026-07-28 request must carry.
 * Notifications are exempt and must never be routed through this parser.
 */
public class McpRequestMeta {

    /** The declared protocol version; read by {@code HeaderValidator} and {@code McpApiManager}. */
    private final String protocolVersion;

    /**
     * The declared client capabilities.
     * <p>
     * Retained as the parsed content of the validated envelope, but deliberately not exposed:
     * the accessor that used to return it had no caller anywhere in this plugin, so it was
     * removed rather than left as an untested public surface. Their presence in the request is
     * still <em>required</em> -- the schema declares
     * {@code "io.modelcontextprotocol/clientCapabilities": ClientCapabilities} with no
     * {@code ?}, and {@link #parse} enforces that -- so this field records what the caller sent
     * to satisfy that requirement. Add an accessor back when a handler actually needs to branch
     * on a capability, together with the test that pins the branch.
     * </p>
     */
    private final Map<String, Object> clientCapabilities;

    /**
     * The declared client identity, empty when not supplied ({@code clientInfo?} is optional in
     * the schema, unlike the capabilities above).
     * <p>
     * Retained and unexposed for the same reason as {@link #clientCapabilities}.
     * </p>
     */
    private final Map<String, Object> clientInfo;

    private McpRequestMeta(final String protocolVersion, final Map<String, Object> clientCapabilities,
            final Map<String, Object> clientInfo) {
        this.protocolVersion = protocolVersion;
        this.clientCapabilities = clientCapabilities;
        this.clientInfo = clientInfo;
    }

    /**
     * Extracts and validates {@code params._meta}.
     *
     * @param params the request params
     * @return the parsed metadata
     * @throws McpError with HTTP 400 and -32602 when a required field is missing
     */
    @SuppressWarnings("unchecked")
    public static McpRequestMeta parse(final Map<String, Object> params) {
        final Object rawMeta = params.get("_meta");
        if (!(rawMeta instanceof Map)) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.InvalidParams, "params._meta is required");
        }
        final Map<String, Object> meta = (Map<String, Object>) rawMeta;
        final Object version = meta.get(McpConstants.META_PROTOCOL_VERSION);
        if (!(version instanceof String)) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.InvalidParams,
                    "params._meta[\"" + McpConstants.META_PROTOCOL_VERSION + "\"] is required");
        }
        final Object capabilities = meta.get(McpConstants.META_CLIENT_CAPABILITIES);
        if (!(capabilities instanceof Map)) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.InvalidParams,
                    "params._meta[\"" + McpConstants.META_CLIENT_CAPABILITIES + "\"] is required");
        }
        final Object info = meta.get(McpConstants.META_CLIENT_INFO);
        return new McpRequestMeta((String) version, (Map<String, Object>) capabilities,
                info instanceof Map ? (Map<String, Object>) info : Collections.<String, Object> emptyMap());
    }

    /**
     * Returns the protocol version the client declared.
     *
     * @return the protocol version string
     */
    public String getProtocolVersion() {
        return protocolVersion;
    }
}
