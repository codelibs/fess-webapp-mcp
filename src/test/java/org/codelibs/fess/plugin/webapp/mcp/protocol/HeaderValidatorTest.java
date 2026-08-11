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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.api.mcp.McpHttpTestSupport;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.junit.jupiter.api.Test;

public class HeaderValidatorTest {

    private McpRequest toolsCall(final String toolName) {
        final Map<String, Object> meta = new HashMap<>();
        meta.put(McpConstants.META_PROTOCOL_VERSION, "2026-07-28");
        meta.put(McpConstants.META_CLIENT_CAPABILITIES, new HashMap<>());
        final Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        params.put("name", toolName);
        final Map<String, Object> envelope = new HashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", params);
        return McpRequest.parse(envelope);
    }

    private McpRequest resourcesRead(final String uri) {
        final Map<String, Object> meta = new HashMap<>();
        meta.put(McpConstants.META_PROTOCOL_VERSION, "2026-07-28");
        meta.put(McpConstants.META_CLIENT_CAPABILITIES, new HashMap<>());
        final Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        params.put("uri", uri);
        final Map<String, Object> envelope = new HashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "resources/read");
        envelope.put("params", params);
        return McpRequest.parse(envelope);
    }

    private McpRequest toolsCallWithBodyVersion(final String toolName, final String protocolVersion) {
        final Map<String, Object> meta = new HashMap<>();
        meta.put(McpConstants.META_PROTOCOL_VERSION, protocolVersion);
        meta.put(McpConstants.META_CLIENT_CAPABILITIES, new HashMap<>());
        final Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        params.put("name", toolName);
        final Map<String, Object> envelope = new HashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", params);
        return McpRequest.parse(envelope);
    }

    private McpRequest toolsList() {
        final Map<String, Object> meta = new HashMap<>();
        meta.put(McpConstants.META_PROTOCOL_VERSION, "2026-07-28");
        meta.put(McpConstants.META_CLIENT_CAPABILITIES, new HashMap<>());
        final Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        final Map<String, Object> envelope = new HashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/list");
        envelope.put("params", params);
        return McpRequest.parse(envelope);
    }

    @Test
    public void testMissingProtocolVersionHeaderIsHeaderMismatchNotUnsupportedVersion() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, toolsCall("search")));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode(), "a missing required header is -32020, not -32022");
        assertEquals(400, error.getHttpStatus());
    }

    /**
     * Same missing-header scenario as above, but with a body protocol version that is not
     * in {@code SUPPORTED_PROTOCOL_VERSIONS}. This is the case the brief actually warns
     * about: an implementation that checks version support before header presence would
     * answer this exact request with -32022, since -32022 only fires on an unsupported
     * version. The Step-1 test above always uses a supported body version ("2026-07-28"),
     * so it cannot by itself distinguish "checked presence first" from "checked version
     * support first" -- both produce -32020 by coincidence when the body version happens to
     * be supported. This test removes that coincidence.
     */
    @Test
    public void testMissingProtocolVersionHeaderIsHeaderMismatchEvenWithUnsupportedBodyVersion() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpRequest parsed = toolsCallWithBodyVersion("search", "2025-06-18");
        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, parsed));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode(),
                "a missing required header is -32020 regardless of whether the body version is supported");
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testMissingMethodHeaderIsRejectedByRequirePresent() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, toolsCall("search")));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testMissingNameHeaderIsRejectedByRequirePresentForToolsCall() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");

        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, toolsCall("search")));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testMissingNameHeaderIsRejectedByRequirePresentForResourcesRead() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "resources/read");

        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, resourcesRead("urn:doc:1")));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
    }

    @Test
    public void testNameHeaderIsNotRequiredForMethodsThatDoNotCarryOne() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/list");

        // No McpError: tools/list does not mirror Mcp-Name, so its absence is fine.
        HeaderValidator.requirePresent(request, toolsList());
    }

    @Test
    public void testMethodHeaderMustMatchBody() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/list");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpRequest parsed = toolsCall("search");
        final McpError error = assertThrows(McpError.class,
                () -> HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams())));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testProtocolVersionHeaderMustMatchBody() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2025-06-18");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpRequest parsed = toolsCall("search");
        final McpError error = assertThrows(McpError.class,
                () -> HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams())));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
    }

    @Test
    public void testNameHeaderMustMatchParamsNameForToolsCall() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "not-search");

        final McpRequest parsed = toolsCall("search");
        final McpError error = assertThrows(McpError.class,
                () -> HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams())));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
    }

    @Test
    public void testNameHeaderMustMatchParamsUriForResourcesRead() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "resources/read");
        request.addHeader(McpConstants.HEADER_NAME, "urn:doc:1");

        final McpRequest parsed = resourcesRead("urn:doc:1");
        HeaderValidator.requirePresent(request, parsed);
        HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams()));

        final MockletHttpServletRequestImpl mismatched = McpHttpTestSupport.newRequest("POST", "/mcp");
        mismatched.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        mismatched.addHeader(McpConstants.HEADER_METHOD, "resources/read");
        mismatched.addHeader(McpConstants.HEADER_NAME, "urn:doc:2");
        final McpError error = assertThrows(McpError.class,
                () -> HeaderValidator.requireMatches(mismatched, parsed, McpRequestMeta.parse(parsed.getParams())));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
    }

    @Test
    public void testStrayNameHeaderIsIgnoredForMethodsThatDoNotCarryOne() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/list");
        // tools/list does not mirror Mcp-Name; a stray header must not be validated against
        // anything and must not fail the request.
        request.addHeader(McpConstants.HEADER_NAME, "whatever");

        final McpRequest parsed = toolsList();
        HeaderValidator.requirePresent(request, parsed);
        HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams()));
    }

    @Test
    public void testNameHeaderPresentButBodyFieldAbsentIsHeaderMismatch() {
        // params carries no "name" field at all (not merely null), while the client still
        // sent Mcp-Name -- the header must lose against an absent body value, not be
        // silently accepted because there is nothing to compare against.
        final Map<String, Object> meta = new HashMap<>();
        meta.put(McpConstants.META_PROTOCOL_VERSION, "2026-07-28");
        meta.put(McpConstants.META_CLIENT_CAPABILITIES, new HashMap<>());
        final Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        final Map<String, Object> envelope = new HashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", params);
        final McpRequest parsed = McpRequest.parse(envelope);

        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpError error = assertThrows(McpError.class,
                () -> HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams())));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testBase64SentinelIsDecodedBeforeComparing() {
        final String encoded =
                "=?base64?" + java.util.Base64.getEncoder().encodeToString("検索".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "?=";
        assertEquals("検索", HeaderValidator.decodeSentinel(encoded));
        assertEquals("search", HeaderValidator.decodeSentinel("search"));
    }

    @Test
    public void testDecodeSentinelReturnsNullForNullInput() {
        assertEquals(null, HeaderValidator.decodeSentinel(null));
    }

    @Test
    public void testMalformedBase64SentinelIsHeaderMismatch() {
        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.decodeSentinel("=?base64?not-valid-base64!!?="));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testOverlappingSentinelDelimitersIsHeaderMismatch() {
        // "=?base64?=" is ten characters: the prefix is nine and the suffix two, so the single
        // '?' at index 8 serves as the last character of the prefix AND the first of the suffix.
        // Both startsWith and endsWith hold, and slicing the payload asks for substring(9, 8) --
        // a StringIndexOutOfBoundsException, which is a *sibling* of IllegalArgumentException,
        // not a subtype, so the malformed-base64 catch does not see it. Unguarded it escapes to
        // McpApiManager's catch (Throwable) and is served as HTTP 200 / -32603 with a WARN stack
        // trace, on unauthenticated input evaluated before the rate limiter. assertThrows on
        // McpError is what makes this test fail (Unexpected exception type) if the guard goes.
        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.decodeSentinel("=?base64?="));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus(), "an unparseable sentinel is a client error, not an internal one");
    }

    @Test
    public void testOverlappingSentinelDelimitersInNameHeaderIsHttp400() {
        // The same value as it actually arrives: an Mcp-Name header on a real tools/call. Pins
        // that the guard is reached on the production path, not only when decodeSentinel is
        // called directly.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "=?base64?=");

        final McpRequest parsed = toolsCall("search");
        HeaderValidator.requirePresent(request, parsed);

        final McpError error = assertThrows(McpError.class,
                () -> HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams())));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testSentinelWithEmptyPayloadDecodesToEmptyString() {
        // One character longer than the overlap case, and the boundary the length guard must sit
        // exactly at: "=?base64??=" has its own '?' for each delimiter and an empty payload
        // between them, which is a well-formed sentinel base64-decoding to "". If the guard were
        // off by one (rejecting length 11 too) this would throw instead. The empty result is left
        // to the body comparison, which rejects it like any other mismatch -- nothing is named "".
        assertEquals("", HeaderValidator.decodeSentinel("=?base64??="));
    }

    @Test
    public void testNameHeaderMatchesDecodedNonAsciiToolName() {
        final String toolName = "検索";
        final String encoded = "=?base64?"
                + java.util.Base64.getEncoder().encodeToString(toolName.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "?=";
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, encoded);

        final McpRequest parsed = toolsCall(toolName);
        HeaderValidator.requirePresent(request, parsed);
        HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams()));
    }

    // ------------------------------------------------------------------
    // A repeated metadata header is rejected.
    //
    // The MCP 2026-07-28 spec does NOT require this: its Server Validation section enumerates its
    // failure conditions exhaustively as a missing required header, a header value that disagrees
    // with the body, and a header value containing invalid characters -- duplicates are never
    // mentioned, so accepting them was already conformant. This is hardening of the threat model
    // the spec cites as the REASON for the validation: a load balancer routing on the header value
    // while the MCP server executes based on the body value. request.getHeader() is specified to
    // return only "the first head in the request", and Tomcat keeps duplicates as separate fields
    // and rejects duplicates for Content-Length alone, so "Mcp-Method: tools/call" followed by
    // "Mcp-Method: tools/list" used to pass validation against a tools/call body while a
    // last-reading gateway routed the request as tools/list.
    // ------------------------------------------------------------------

    @Test
    public void testDuplicateMethodHeaderIsHeaderMismatch() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/list");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, toolsCall("search")));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode(),
                "the first occurrence agrees with the body, so only a duplicate check can reject this");
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testDuplicateProtocolVersionHeaderIsHeaderMismatch() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2025-06-18");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, toolsCall("search")));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode(), "a duplicate is -32020, not -32022: the version itself is supported");
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testDuplicateNameHeaderIsHeaderMismatch() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");
        request.addHeader(McpConstants.HEADER_NAME, "get_index_stats");

        final McpError error = assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, toolsCall("search")));
        assertEquals(ErrorCode.HeaderMismatch, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testRepeatingAHeaderWithAnIdenticalValueIsStillRejected() {
        // A repeated header is ambiguous to an intermediary regardless of whether the two values
        // agree with each other -- and "the values happen to be equal" is not a property this
        // server can verify cheaply for the general case, so the rule is one occurrence, full stop.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        assertThrows(McpError.class, () -> HeaderValidator.requirePresent(request, toolsCall("search")));
    }

    @Test
    public void testARepeatedNameHeaderIsIgnoredForMethodsThatDoNotCarryOne() {
        // Scoped to the headers this method actually requires, exactly as the single-occurrence
        // checks are: tools/list does not mirror Mcp-Name, so a stray -- even repeated -- one is
        // not part of its metadata and must not turn a valid request into a 400.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/list");
        request.addHeader(McpConstants.HEADER_NAME, "stray");
        request.addHeader(McpConstants.HEADER_NAME, "also-stray");

        HeaderValidator.requirePresent(request, toolsList());
    }

    @Test
    public void testMatchingHeadersPass() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28");
        request.addHeader(McpConstants.HEADER_METHOD, "tools/call");
        request.addHeader(McpConstants.HEADER_NAME, "search");

        final McpRequest parsed = toolsCall("search");
        HeaderValidator.requirePresent(request, parsed);
        HeaderValidator.requireMatches(request, parsed, McpRequestMeta.parse(parsed.getParams()));
    }
}
