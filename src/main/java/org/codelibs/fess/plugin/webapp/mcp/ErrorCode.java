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
 * Standard JSON-RPC 2.0 error codes for MCP API.
 */
public enum ErrorCode {
    /** Parse error: Invalid JSON was received by the server. */
    ParseError(-32700),
    /** Invalid Request: The JSON sent is not a valid Request object. */
    InvalidRequest(-32600),
    /** Method not found: The method does not exist or is not available. */
    MethodNotFound(-32601),
    /** Invalid params: Invalid method parameter(s). */
    InvalidParams(-32602),
    /** Internal error: Internal JSON-RPC error. */
    InternalError(-32603);

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
