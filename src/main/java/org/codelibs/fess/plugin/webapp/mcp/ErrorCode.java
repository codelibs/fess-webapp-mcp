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
package org.codelibs.fess.plugin.webapp.mcp;

/**
 * Standard JSON-RPC 2.0 error codes plus the MCP-specific codes added in protocol
 * revision 2026-07-28, for MCP API.
 *
 * <p>Deliberately carries no HTTP status: in MCP 2026-07-28 the same JSON-RPC code can
 * map to different HTTP statuses depending on where it is raised. For example,
 * {@link #InvalidParams} (-32602) is a MUST 400 for a malformed {@code params._meta},
 * but is a 200 with a JSON-RPC error body for a resource/prompt/tool that does not
 * exist or an invalid cursor. The status is decided by the emitting site, not by this
 * enum.</p>
 */
public enum ErrorCode {
    /** Parse error: Invalid JSON was received by the server. */
    ParseError(-32700),
    /** Invalid Request: The JSON sent is not a valid Request object. */
    InvalidRequest(-32600),
    /** Method not found: The method does not exist or is not available. */
    MethodNotFound(-32601),
    /** Invalid params: Invalid method parameter(s). Also covers resource/prompt/tool not found in MCP 2026-07-28. */
    InvalidParams(-32602),
    /** Internal error: Internal JSON-RPC error. */
    InternalError(-32603),
    /** An HTTP metadata header disagrees with the request body, or a required header is missing. */
    HeaderMismatch(-32020),
    /** The request needs a client capability the client did not declare. Never emitted by this server. */
    MissingRequiredClientCapability(-32021),
    /** The requested protocol version is not implemented by this server. */
    UnsupportedProtocolVersion(-32022);

    /** The numeric error code. */
    private final int code;

    /**
     * Creates an ErrorCode with the specified numeric code.
     *
     * @param code the numeric error code
     */
    ErrorCode(final int code) {
        this.code = code;
    }

    /**
     * Returns the numeric error code.
     *
     * @return the error code
     */
    public int getCode() {
        return code;
    }
}
