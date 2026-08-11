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
package org.codelibs.fess.plugin.webapp.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.junit.jupiter.api.Test;

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

        assertEquals(code, exception.getCode(), "Error code should match");
        assertEquals(message, exception.getMessage(), "Message should match");
    }

    @Test
    public void testConstructorWithCodeMessageAndCause() {
        final ErrorCode code = ErrorCode.InternalError;
        final String message = "Internal processing error";
        final Throwable cause = new RuntimeException("Root cause");

        final McpApiException exception = new McpApiException(code, message, cause);

        assertEquals(code, exception.getCode(), "Error code should match");
        assertEquals(message, exception.getMessage(), "Message should match");
        assertNotNull(exception.getCause(), "Cause should not be null");
        assertEquals("Root cause", exception.getCause().getMessage(), "Cause message should match");
    }

    @Test
    public void testAllErrorCodes() {
        // Test that exception can be created with all error codes
        final ErrorCode[] codes = ErrorCode.values();

        for (final ErrorCode code : codes) {
            final McpApiException exception = new McpApiException(code, "Test message");
            assertEquals(code, exception.getCode(), "Error code should match");
        }
    }

    @Test
    public void testGetCodeReturnsCorrectErrorCode() {
        final McpApiException exception = new McpApiException(ErrorCode.ParseError, "Parse error occurred");
        final ErrorCode returnedCode = exception.getCode();

        assertNotNull(returnedCode, "Returned error code should not be null");
        assertEquals(ErrorCode.ParseError, returnedCode, "Should return ParseError");
        assertEquals(-32700, returnedCode.getCode(), "Error code value should be -32700");
    }

    @Test
    public void testExceptionMessageWithDifferentCodes() {
        // Test exception messages with different error codes
        final McpApiException parseException = new McpApiException(ErrorCode.ParseError, "JSON parse failed");
        assertEquals("JSON parse failed", parseException.getMessage(), "Message should match");
        assertEquals(ErrorCode.ParseError, parseException.getCode(), "Code should be ParseError");

        final McpApiException methodException = new McpApiException(ErrorCode.MethodNotFound, "Method does not exist");
        assertEquals("Method does not exist", methodException.getMessage(), "Message should match");
        assertEquals(ErrorCode.MethodNotFound, methodException.getCode(), "Code should be MethodNotFound");
    }

    @Test
    public void testExceptionWithNullMessage() {
        final McpApiException exception = new McpApiException(ErrorCode.InternalError, null);
        assertEquals(ErrorCode.InternalError, exception.getCode(), "Error code should be InternalError");
        assertEquals(null, exception.getMessage(), "Message should be null");
    }

    @Test
    public void testExceptionWithEmptyMessage() {
        final String emptyMessage = "";
        final McpApiException exception = new McpApiException(ErrorCode.InvalidParams, emptyMessage);

        assertEquals(ErrorCode.InvalidParams, exception.getCode(), "Error code should be InvalidParams");
        assertEquals(emptyMessage, exception.getMessage(), "Message should be empty string");
    }

    @Test
    public void testExceptionCauseChain() {
        final RuntimeException rootCause = new RuntimeException("Root cause");
        final IllegalArgumentException middleCause = new IllegalArgumentException("Middle cause", rootCause);
        final McpApiException exception = new McpApiException(ErrorCode.InternalError, "Top level error", middleCause);

        assertNotNull(exception.getCause(), "Cause should not be null");
        assertEquals(middleCause, exception.getCause(), "Immediate cause should be IllegalArgumentException");
        assertEquals(rootCause, exception.getCause().getCause(), "Root cause should be RuntimeException");
    }

    @Test
    public void testExceptionInheritance() {
        final McpApiException exception = new McpApiException(ErrorCode.InternalError, "Test");

        assertTrue(exception instanceof McpApiException, "Should be instance of McpApiException");
        assertTrue(exception instanceof RuntimeException, "Should be instance of RuntimeException");
        assertTrue(exception instanceof Exception, "Should be instance of Exception");
    }

    @Test
    public void testExceptionWithLongMessage() {
        final StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longMessage.append("This is a very long error message. ");
        }

        final McpApiException exception = new McpApiException(ErrorCode.InternalError, longMessage.toString());

        assertEquals(ErrorCode.InternalError, exception.getCode(), "Error code should match");
        assertEquals(longMessage.toString(), exception.getMessage(), "Message should match");
        assertTrue(exception.getMessage().length() > 10000, "Message should be very long");
    }

    @Test
    public void testExceptionWithSpecialCharactersInMessage() {
        final String specialMessage = "Error: 日本語 テスト \n\t\r Special chars: @#$%^&*()";
        final McpApiException exception = new McpApiException(ErrorCode.InvalidRequest, specialMessage);

        assertEquals(specialMessage, exception.getMessage(), "Message should contain special characters");
        assertEquals(ErrorCode.InvalidRequest, exception.getCode(), "Error code should be InvalidRequest");
    }

    @Test
    public void testMultipleExceptionsWithSameCode() {
        // Verify that multiple exceptions can be created with the same error code
        final McpApiException exception1 = new McpApiException(ErrorCode.InvalidParams, "First error");
        final McpApiException exception2 = new McpApiException(ErrorCode.InvalidParams, "Second error");

        assertEquals(exception1.getCode(), exception2.getCode(), "Both should have same error code");
        assertTrue(!exception1.getMessage().equals(exception2.getMessage()), "Messages should be different");
    }
}
