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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.api.mcp.McpHttpTestSupport;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.junit.jupiter.api.Test;

public class McpResponseWriterTest {

    private final McpResponseWriter writer = new McpResponseWriter("fess-mcp-server", "15.8.0");

    private MockletHttpServletResponseImpl response() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        return McpHttpTestSupport.newResponse(request);
    }

    @Test
    public void testResultCarriesResultTypeAndServerInfo() {
        final MockletHttpServletResponseImpl response = response();
        writer.writeResult(response, 1, new LinkedHashMap<>(Map.of("tools", java.util.List.of())));
        final String body = McpHttpTestSupport.bodyOf(response);

        assertEquals(200, response.getStatus());
        assertEquals("application/json; charset=UTF-8", response.getContentType());
        assertTrue(body.contains("\"resultType\":\"complete\""), "every result must carry resultType: " + body);
        assertTrue(body.contains("io.modelcontextprotocol/serverInfo"), "serverInfo should ride in _meta: " + body);
    }

    @Test
    public void testExistingMetaEntrySurvivesAlongsideInjectedServerInfo() {
        final MockletHttpServletResponseImpl response = response();
        final Map<String, Object> meta = new LinkedHashMap<>(Map.of("io.modelcontextprotocol/requestId", "abc-123"));
        final Map<String, Object> result = new LinkedHashMap<>(Map.of("tools", java.util.List.of(), "_meta", meta));

        writer.writeResult(response, 1, result);
        final String body = McpHttpTestSupport.bodyOf(response);

        assertTrue(body.contains("\"io.modelcontextprotocol/requestId\":\"abc-123\""),
                "an unrelated _meta entry the handler set must survive the merge: " + body);
        assertTrue(body.contains("io.modelcontextprotocol/serverInfo"), "serverInfo must still be injected: " + body);
    }

    @Test
    public void testExistingServerInfoInMetaWinsOverInjectedValue() {
        final MockletHttpServletResponseImpl response = response();
        final Map<String, Object> handlerServerInfo = new LinkedHashMap<>(Map.of("name", "handler-supplied", "version", "0.0.1"));
        final Map<String, Object> meta = new LinkedHashMap<>(Map.of(McpConstants.META_SERVER_INFO, handlerServerInfo));
        final Map<String, Object> result = new LinkedHashMap<>(Map.of("tools", java.util.List.of(), "_meta", meta));

        writer.writeResult(response, 1, result);
        final String body = McpHttpTestSupport.bodyOf(response);

        assertTrue(body.contains("\"handler-supplied\""), "putIfAbsent means a serverInfo the handler already set must win: " + body);
        assertFalse(body.contains("fess-mcp-server"), "the writer's own serverInfo must not overwrite the handler's: " + body);
    }

    @Test
    public void testExistingResultTypeIsNotOverwritten() {
        final MockletHttpServletResponseImpl response = response();
        final Map<String, Object> result = new LinkedHashMap<>(Map.of("tools", java.util.List.of(), "resultType", "partial"));

        writer.writeResult(response, 1, result);
        final String body = McpHttpTestSupport.bodyOf(response);

        assertTrue(body.contains("\"resultType\":\"partial\""), "an existing resultType must not be overwritten: " + body);
        assertFalse(body.contains("\"resultType\":\"complete\""), "the default resultType must not be forced in: " + body);
    }

    @Test
    public void testErrorWithoutIdOmitsTheIdKey() {
        final MockletHttpServletResponseImpl response = response();
        writer.writeError(response, null, false, new McpError(400, ErrorCode.ParseError, "malformed JSON"));
        final String body = McpHttpTestSupport.bodyOf(response);

        assertEquals(400, response.getStatus());
        assertEquals("application/json; charset=UTF-8", response.getContentType());
        assertFalse(body.contains("\"id\""), "RequestId is string|number; null is not in the type: " + body);
        assertTrue(body.contains("-32700"));
        assertFalse(body.contains("resultType"), "error responses do not extend Result");
    }

    @Test
    public void testResultEnvelopeIncludesJsonrpcVersion() {
        // No test anywhere previously asserted the literal envelope shape; deleting the
        // "jsonrpc" entry from writeResult's envelope would leave the suite green.
        final MockletHttpServletResponseImpl response = response();
        writer.writeResult(response, 1, new LinkedHashMap<>(Map.of("tools", java.util.List.of())));
        final String body = McpHttpTestSupport.bodyOf(response);
        assertTrue(body.contains("\"jsonrpc\":\"2.0\""), "every JSON-RPC response must carry the version: " + body);
    }

    @Test
    public void testErrorEnvelopeIncludesJsonrpcVersionAndIdWhenKnown() {
        // testErrorWithoutIdOmitsTheIdKey only pins the negative (hasId == false); this pins the
        // positive so inverting that hasId check, or dropping "jsonrpc" from the error envelope,
        // would also be caught.
        final MockletHttpServletResponseImpl response = response();
        writer.writeError(response, 3, true, new McpError(400, ErrorCode.ParseError, "malformed JSON"));
        final String body = McpHttpTestSupport.bodyOf(response);

        assertTrue(body.contains("\"jsonrpc\":\"2.0\""), "every JSON-RPC response must carry the version: " + body);
        assertTrue(body.contains("\"id\":3"), "a known id must be present, not omitted, when hasId is true: " + body);
    }

    @Test
    public void testErrorDataIsEmitted() {
        final MockletHttpServletResponseImpl response = response();
        writer.writeError(response, 3, true, new McpError(400, ErrorCode.UnsupportedProtocolVersion, "unsupported",
                Map.of("supported", java.util.List.of("2026-07-28"), "requested", "2025-06-18")));
        final String body = McpHttpTestSupport.bodyOf(response);

        assertTrue(body.contains("\"supported\""), body);
        assertTrue(body.contains("\"requested\":\"2025-06-18\""), "the -32022 payload requires both fields: " + body);
    }

    @Test
    public void testRetryAfterHeaderIsSetFromErrorData() {
        final MockletHttpServletResponseImpl response = response();
        writer.writeError(response, 1, true,
                new McpError(429, ErrorCode.InternalError, "rate limit exceeded", Map.of("retryAfterSeconds", 60)));

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
    }

    @Test
    public void testRetryAfterHeaderIsAbsentWithoutRetryAfterSecondsData() {
        final MockletHttpServletResponseImpl response = response();
        writer.writeError(response, 1, true, new McpError(400, ErrorCode.ParseError, "malformed JSON"));

        assertNull(response.getHeader("Retry-After"), "only an error carrying retryAfterSeconds data sets the header");
    }

    @Test
    public void testNotificationGets202WithNoBody() {
        final MockletHttpServletResponseImpl response = response();
        writer.writeAccepted(response);

        assertEquals(202, response.getStatus());
        assertEquals("", McpHttpTestSupport.bodyOf(response), "202 must have no body");
        assertNull(response.getContentType(), "202 must not set a content type");
    }
}
