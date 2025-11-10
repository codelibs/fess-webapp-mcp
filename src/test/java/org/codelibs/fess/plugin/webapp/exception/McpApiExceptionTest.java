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

    @Test
    public void testGetCodeReturnsCorrectErrorCode() {
        final McpApiException exception = new McpApiException(ErrorCode.ParseError, "Parse error occurred");
        final ErrorCode returnedCode = exception.getCode();

        assertNotNull("Returned error code should not be null", returnedCode);
        assertEquals("Should return ParseError", ErrorCode.ParseError, returnedCode);
        assertEquals("Error code value should be -32700", -32700, returnedCode.getCode());
    }

    @Test
    public void testExceptionMessageWithDifferentCodes() {
        // Test exception messages with different error codes
        final McpApiException parseException = new McpApiException(ErrorCode.ParseError, "JSON parse failed");
        assertEquals("Message should match", "JSON parse failed", parseException.getMessage());
        assertEquals("Code should be ParseError", ErrorCode.ParseError, parseException.getCode());

        final McpApiException methodException = new McpApiException(ErrorCode.MethodNotFound, "Method does not exist");
        assertEquals("Message should match", "Method does not exist", methodException.getMessage());
        assertEquals("Code should be MethodNotFound", ErrorCode.MethodNotFound, methodException.getCode());
    }

    @Test
    public void testExceptionWithNullMessage() {
        final McpApiException exception = new McpApiException(ErrorCode.InternalError, null);
        assertEquals("Error code should be InternalError", ErrorCode.InternalError, exception.getCode());
        assertEquals("Message should be null", null, exception.getMessage());
    }

    @Test
    public void testExceptionWithEmptyMessage() {
        final String emptyMessage = "";
        final McpApiException exception = new McpApiException(ErrorCode.InvalidParams, emptyMessage);

        assertEquals("Error code should be InvalidParams", ErrorCode.InvalidParams, exception.getCode());
        assertEquals("Message should be empty string", emptyMessage, exception.getMessage());
    }

    @Test
    public void testExceptionCauseChain() {
        final RuntimeException rootCause = new RuntimeException("Root cause");
        final IllegalArgumentException middleCause = new IllegalArgumentException("Middle cause", rootCause);
        final McpApiException exception = new McpApiException(ErrorCode.InternalError, "Top level error", middleCause);

        assertNotNull("Cause should not be null", exception.getCause());
        assertEquals("Immediate cause should be IllegalArgumentException", middleCause, exception.getCause());
        assertEquals("Root cause should be RuntimeException", rootCause, exception.getCause().getCause());
    }

    @Test
    public void testExceptionInheritance() {
        final McpApiException exception = new McpApiException(ErrorCode.InternalError, "Test");

        assertTrue("Should be instance of McpApiException", exception instanceof McpApiException);
        assertTrue("Should be instance of RuntimeException", exception instanceof RuntimeException);
        assertTrue("Should be instance of Exception", exception instanceof Exception);
    }

    @Test
    public void testExceptionWithLongMessage() {
        final StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longMessage.append("This is a very long error message. ");
        }

        final McpApiException exception = new McpApiException(ErrorCode.InternalError, longMessage.toString());

        assertEquals("Error code should match", ErrorCode.InternalError, exception.getCode());
        assertEquals("Message should match", longMessage.toString(), exception.getMessage());
        assertTrue("Message should be very long", exception.getMessage().length() > 10000);
    }

    @Test
    public void testExceptionWithSpecialCharactersInMessage() {
        final String specialMessage = "Error: 日本語 テスト \n\t\r Special chars: @#$%^&*()";
        final McpApiException exception = new McpApiException(ErrorCode.InvalidRequest, specialMessage);

        assertEquals("Message should contain special characters", specialMessage, exception.getMessage());
        assertEquals("Error code should be InvalidRequest", ErrorCode.InvalidRequest, exception.getCode());
    }

    @Test
    public void testMultipleExceptionsWithSameCode() {
        // Verify that multiple exceptions can be created with the same error code
        final McpApiException exception1 = new McpApiException(ErrorCode.InvalidParams, "First error");
        final McpApiException exception2 = new McpApiException(ErrorCode.InvalidParams, "Second error");

        assertEquals("Both should have same error code", exception1.getCode(), exception2.getCode());
        assertTrue("Messages should be different", !exception1.getMessage().equals(exception2.getMessage()));
    }
}
