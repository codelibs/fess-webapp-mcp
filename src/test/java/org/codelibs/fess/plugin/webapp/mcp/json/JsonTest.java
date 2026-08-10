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
package org.codelibs.fess.plugin.webapp.mcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.junit.jupiter.api.Test;

public class JsonTest {

    @Test
    public void testEmptyBodyIsParseError() {
        final McpError error = assertThrows(McpError.class, () -> Json.parseObject(""));
        assertEquals(ErrorCode.ParseError, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testNullBodyIsParseError() {
        final McpError error = assertThrows(McpError.class, () -> Json.parseObject(null));
        assertEquals(ErrorCode.ParseError, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testMalformedJsonIsParseError() {
        final McpError error = assertThrows(McpError.class, () -> Json.parseObject("{not valid json"));
        assertEquals(ErrorCode.ParseError, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testArrayBodyIsRejected() {
        // parser.map() does not throw for an array body on its own (it silently returns an
        // empty map), so this proves Json.parseObject adds the explicit shape check that
        // makes batching a 400 instead of a silently empty request.
        final McpError error = assertThrows(McpError.class, () -> Json.parseObject("[1,2,3]"));
        assertEquals(ErrorCode.ParseError, error.getErrorCode(), "a JSON array body must be rejected, not silently emptied");
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testScalarBodyIsRejected() {
        final McpError error = assertThrows(McpError.class, () -> Json.parseObject("\"hello\""));
        assertEquals(ErrorCode.ParseError, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testValidObjectIsParsed() {
        final Map<String, Object> result = Json.parseObject("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
        assertEquals("2.0", result.get("jsonrpc"));
        assertEquals("tools/list", result.get("method"));
        assertEquals(1, result.get("id"));
    }

    @Test
    public void testWriteSerializesMap() {
        final Map<String, Object> value = new LinkedHashMap<>();
        value.put("jsonrpc", "2.0");
        value.put("id", 1);
        final String json = Json.write(value);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""), "expected jsonrpc field in: " + json);
        assertTrue(json.contains("\"id\":1"), "expected id field in: " + json);
    }
}
