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
package org.codelibs.fess.plugin.webapp.exception;

import org.codelibs.fess.exception.FessSystemException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;

/**
 * Exception thrown when an invalid query is encountered in the MCP API.
 */
public class McpApiException extends FessSystemException {

    private static final long serialVersionUID = 1L;

    private ErrorCode code;

    public McpApiException(final ErrorCode code, final String message) {
        super(message);
        this.code = code;
    }

    public McpApiException(final ErrorCode code, final String message, final Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
