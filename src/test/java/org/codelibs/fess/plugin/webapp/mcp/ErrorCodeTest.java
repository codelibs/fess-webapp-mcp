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
package org.codelibs.fess.plugin.webapp.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Test class for ErrorCode enum.
 *
 * This test validates that the JSON-RPC 2.0 standard error codes
 * are correctly defined according to the specification.
 */
public class ErrorCodeTest {

    @Test
    public void testParseError() {
        assertEquals("ParseError code should be -32700", -32700, ErrorCode.ParseError.getCode());
    }

    @Test
    public void testInvalidRequest() {
        assertEquals("InvalidRequest code should be -32600", -32600, ErrorCode.InvalidRequest.getCode());
    }

    @Test
    public void testMethodNotFound() {
        assertEquals("MethodNotFound code should be -32601", -32601, ErrorCode.MethodNotFound.getCode());
    }

    @Test
    public void testInvalidParams() {
        assertEquals("InvalidParams code should be -32602", -32602, ErrorCode.InvalidParams.getCode());
    }

    @Test
    public void testInternalError() {
        assertEquals("InternalError code should be -32603", -32603, ErrorCode.InternalError.getCode());
    }

    @Test
    public void testEnumValues() {
        final ErrorCode[] values = ErrorCode.values();
        assertEquals("Should have 5 error codes", 5, values.length);
    }

    @Test
    public void testValueOf() {
        // Test valueOf method for enum
        assertEquals("valueOf ParseError should work", ErrorCode.ParseError, ErrorCode.valueOf("ParseError"));
        assertEquals("valueOf InvalidRequest should work", ErrorCode.InvalidRequest, ErrorCode.valueOf("InvalidRequest"));
        assertEquals("valueOf MethodNotFound should work", ErrorCode.MethodNotFound, ErrorCode.valueOf("MethodNotFound"));
        assertEquals("valueOf InvalidParams should work", ErrorCode.InvalidParams, ErrorCode.valueOf("InvalidParams"));
        assertEquals("valueOf InternalError should work", ErrorCode.InternalError, ErrorCode.valueOf("InternalError"));
    }

    @Test
    public void testAllErrorCodesAreUnique() {
        final ErrorCode[] values = ErrorCode.values();
        final java.util.Set<Integer> codes = new java.util.HashSet<>();

        for (final ErrorCode errorCode : values) {
            assertTrue("Error code " + errorCode.name() + " should be unique", codes.add(errorCode.getCode()));
        }

        assertEquals("All error codes should be unique", values.length, codes.size());
    }

    @Test
    public void testErrorCodesAreNegative() {
        // JSON-RPC 2.0 error codes should be negative
        final ErrorCode[] values = ErrorCode.values();

        for (final ErrorCode errorCode : values) {
            assertTrue("Error code " + errorCode.name() + " should be negative", errorCode.getCode() < 0);
        }
    }

    @Test
    public void testErrorCodeRange() {
        // JSON-RPC 2.0 standard error codes should be in range -32768 to -32000
        final ErrorCode[] values = ErrorCode.values();

        for (final ErrorCode errorCode : values) {
            final int code = errorCode.getCode();
            assertTrue("Error code " + errorCode.name() + " should be >= -32768", code >= -32768);
            assertTrue("Error code " + errorCode.name() + " should be <= -32000", code <= -32000);
        }
    }

    @Test
    public void testSpecificErrorCodeValues() {
        // Verify specific JSON-RPC 2.0 error code values according to spec
        final java.util.Map<ErrorCode, Integer> expectedCodes = new java.util.HashMap<>();
        expectedCodes.put(ErrorCode.ParseError, -32700);
        expectedCodes.put(ErrorCode.InvalidRequest, -32600);
        expectedCodes.put(ErrorCode.MethodNotFound, -32601);
        expectedCodes.put(ErrorCode.InvalidParams, -32602);
        expectedCodes.put(ErrorCode.InternalError, -32603);

        for (final java.util.Map.Entry<ErrorCode, Integer> entry : expectedCodes.entrySet()) {
            assertEquals("Error code " + entry.getKey().name() + " should have correct value", entry.getValue().intValue(),
                    entry.getKey().getCode());
        }
    }
}
