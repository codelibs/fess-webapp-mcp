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

import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;

/**
 * A protocol failure that carries both the JSON-RPC error code and the HTTP status
 * the MCP transport mandates for that particular failure site.
 */
public class McpError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;
    private final ErrorCode errorCode;
    private final transient Map<String, Object> data;

    /**
     * Creates a protocol error.
     *
     * @param httpStatus the HTTP status to send
     * @param errorCode the JSON-RPC error code
     * @param message the human-readable message
     * @param data optional structured payload for {@code error.data}, may be null
     */
    public McpError(final int httpStatus, final ErrorCode errorCode, final String message, final Map<String, Object> data) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.data = data;
    }

    /**
     * Creates a protocol error without structured data.
     *
     * @param httpStatus the HTTP status to send
     * @param errorCode the JSON-RPC error code
     * @param message the human-readable message
     */
    public McpError(final int httpStatus, final ErrorCode errorCode, final String message) {
        this(httpStatus, errorCode, message, null);
    }

    /**
     * Returns the HTTP status for this failure.
     *
     * @return the HTTP status code
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * Returns the JSON-RPC error code.
     *
     * @return the error code
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the structured payload for {@code error.data}.
     *
     * @return the data map, or null when there is none
     */
    public Map<String, Object> getData() {
        return data;
    }
}
