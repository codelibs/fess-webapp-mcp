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
}
