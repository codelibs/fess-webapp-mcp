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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Test class for ErrorCode enum.
 *
 * This test validates that the JSON-RPC 2.0 standard error codes
 * are correctly defined according to the specification.
 */
public class ErrorCodeTest {

    @Test
    public void testParseError() {
        assertEquals(-32700, ErrorCode.ParseError.getCode(), "ParseError code should be -32700");
    }

    @Test
    public void testInvalidRequest() {
        assertEquals(-32600, ErrorCode.InvalidRequest.getCode(), "InvalidRequest code should be -32600");
    }

    @Test
    public void testMethodNotFound() {
        assertEquals(-32601, ErrorCode.MethodNotFound.getCode(), "MethodNotFound code should be -32601");
    }

    @Test
    public void testInvalidParams() {
        assertEquals(-32602, ErrorCode.InvalidParams.getCode(), "InvalidParams code should be -32602");
    }

    @Test
    public void testInternalError() {
        assertEquals(-32603, ErrorCode.InternalError.getCode(), "InternalError code should be -32603");
    }

    @Test
    public void testResourceNotFoundIsRemoved() {
        for (final ErrorCode code : ErrorCode.values()) {
            assertTrue(code.getCode() != -32002, "-32002 is retired in MCP 2026-07-28 and must not be emitted");
        }
    }

    @Test
    public void testNewProtocolErrorCodes() {
        assertEquals(-32020, ErrorCode.HeaderMismatch.getCode());
        assertEquals(-32021, ErrorCode.MissingRequiredClientCapability.getCode());
        assertEquals(-32022, ErrorCode.UnsupportedProtocolVersion.getCode());
    }

    @Test
    public void testErrorCodeCount() {
        assertEquals(8, ErrorCode.values().length, "5 JSON-RPC + 3 MCP-specific codes");
    }

    @Test
    public void testValueOf() {
        // Test valueOf method for enum
        assertEquals(ErrorCode.ParseError, ErrorCode.valueOf("ParseError"), "valueOf ParseError should work");
        assertEquals(ErrorCode.InvalidRequest, ErrorCode.valueOf("InvalidRequest"), "valueOf InvalidRequest should work");
        assertEquals(ErrorCode.MethodNotFound, ErrorCode.valueOf("MethodNotFound"), "valueOf MethodNotFound should work");
        assertEquals(ErrorCode.InvalidParams, ErrorCode.valueOf("InvalidParams"), "valueOf InvalidParams should work");
        assertEquals(ErrorCode.InternalError, ErrorCode.valueOf("InternalError"), "valueOf InternalError should work");
        assertEquals(ErrorCode.HeaderMismatch, ErrorCode.valueOf("HeaderMismatch"), "valueOf HeaderMismatch should work");
        assertEquals(ErrorCode.MissingRequiredClientCapability, ErrorCode.valueOf("MissingRequiredClientCapability"),
                "valueOf MissingRequiredClientCapability should work");
        assertEquals(ErrorCode.UnsupportedProtocolVersion, ErrorCode.valueOf("UnsupportedProtocolVersion"),
                "valueOf UnsupportedProtocolVersion should work");
    }

    @Test
    public void testAllErrorCodesAreUnique() {
        final ErrorCode[] values = ErrorCode.values();
        final java.util.Set<Integer> codes = new java.util.HashSet<>();

        for (final ErrorCode errorCode : values) {
            assertTrue(codes.add(errorCode.getCode()), "Error code " + errorCode.name() + " should be unique");
        }

        assertEquals(values.length, codes.size(), "All error codes should be unique");
    }

    @Test
    public void testErrorCodesAreNegative() {
        // JSON-RPC 2.0 error codes should be negative
        final ErrorCode[] values = ErrorCode.values();

        for (final ErrorCode errorCode : values) {
            assertTrue(errorCode.getCode() < 0, "Error code " + errorCode.name() + " should be negative");
        }
    }

    @Test
    public void testErrorCodeRange() {
        // JSON-RPC 2.0 standard error codes should be in range -32768 to -32000
        final ErrorCode[] values = ErrorCode.values();

        for (final ErrorCode errorCode : values) {
            final int code = errorCode.getCode();
            assertTrue(code >= -32768, "Error code " + errorCode.name() + " should be >= -32768");
            assertTrue(code <= -32000, "Error code " + errorCode.name() + " should be <= -32000");
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
        expectedCodes.put(ErrorCode.HeaderMismatch, -32020);
        expectedCodes.put(ErrorCode.MissingRequiredClientCapability, -32021);
        expectedCodes.put(ErrorCode.UnsupportedProtocolVersion, -32022);

        for (final java.util.Map.Entry<ErrorCode, Integer> entry : expectedCodes.entrySet()) {
            assertEquals(entry.getValue().intValue(), entry.getKey().getCode(),
                    "Error code " + entry.getKey().name() + " should have correct value");
        }
    }
}
