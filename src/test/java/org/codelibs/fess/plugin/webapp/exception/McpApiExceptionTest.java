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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.junit.Test;

/**
 * Test class for McpApiException.
 *
 * This test validates the MCP API exception handling,
 * including error code and message management.
 */
public class McpApiExceptionTest {

    @Test
    public void testConstructorWithCodeAndMessage() {
        final ErrorCode code = ErrorCode.InvalidParams;
        final String message = "Invalid parameter value";

        final McpApiException exception = new McpApiException(code, message);

        assertEquals("Error code should match", code, exception.getCode());
        assertEquals("Message should match", message, exception.getMessage());
    }

    @Test
    public void testConstructorWithCodeMessageAndCause() {
        final ErrorCode code = ErrorCode.InternalError;
        final String message = "Internal processing error";
        final Throwable cause = new RuntimeException("Root cause");

        final McpApiException exception = new McpApiException(code, message, cause);

        assertEquals("Error code should match", code, exception.getCode());
        assertEquals("Message should match", message, exception.getMessage());
        assertNotNull("Cause should not be null", exception.getCause());
        assertEquals("Cause message should match", "Root cause", exception.getCause().getMessage());
    }

    @Test
    public void testAllErrorCodes() {
        // Test that exception can be created with all error codes
        final ErrorCode[] codes = ErrorCode.values();

        for (final ErrorCode code : codes) {
            final McpApiException exception = new McpApiException(code, "Test message");
            assertEquals("Error code should match", code, exception.getCode());
        }
    }
}
