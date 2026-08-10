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
package org.codelibs.fess.plugin.webapp.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.RateLimiter;
import org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpDispatcher;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP-boundary tests for McpApiManager. These run without a DI container,
 * so every config read and the body read must go through a protected seam.
 */
public class McpApiManagerHttpTest {

    /** Test double: supplies a canned body and never touches ComponentUtil. */
    static class TestManager extends McpApiManager {
        String body = "";
        boolean enabled = true;
        int maxBytes = 1_048_576;
        boolean allowedOriginsCalled = false;

        @Override
        protected String readRequestBody(final HttpServletRequest request) throws IOException {
            return body;
        }

        @Override
        protected void writeHeaders(final HttpServletResponse response) {
            // no-op: the real implementation reads api.json.response.headers from the container
        }

        @Override
        protected boolean isEnabled() {
            return enabled;
        }

        @Override
        protected int getRequestMaxBytes() {
            return maxBytes;
        }

        @Override
        protected Set<String> getAllowedOrigins() {
            // no-op: the real implementation reads mcp.allowed.origins from the container.
            // Records whether it ran at all, so tests can assert validateOrigin's
            // presence-check actually skips this call when Origin is absent.
            allowedOriginsCalled = true;
            return Set.of();
        }

        boolean rateLimiterCalled = false;
        RateLimiter rateLimiter = new RateLimiter(60);

        @Override
        protected RateLimiter getRateLimiter() {
            // no-op: the real implementation reads mcp.rate.limit.per.minute from the container
            // on first use. Records whether it ran at all, so tests can assert
            // enforceRateLimit's method-check actually skips this call for methods the rate
            // limit does not cover.
            rateLimiterCalled = true;
            return rateLimiter;
        }

        String authMode = "none";

        @Override
        protected String getAuthMode() {
            // no-op: the real implementation reads mcp.auth.mode from the container. This file's
            // tests are not about authentication (see AuthenticatorTest for that), so every test
            // here runs the default "none" mode unless a test overrides this field.
            return authMode;
        }
    }

    private MockletHttpServletResponseImpl lastResponse;

    private String post(final TestManager manager, final String body, final Map<String, String> headers) throws Exception {
        manager.body = body;
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        headers.forEach(request::addHeader);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        manager.process(request, response, null);
        lastResponse = response;
        return McpHttpTestSupport.bodyOf(response);
    }

    private Map<String, String> modernHeaders(final String method) {
        return Map.of(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28", McpConstants.HEADER_METHOD, method);
    }

    private String modernBody(final String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"," + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
    }

    @Test
    public void testGetIsRejectedWith405() throws Exception {
        final TestManager manager = new TestManager();
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        assertEquals(405, response.getStatus(), "GET must be rejected");
        assertEquals("POST", response.getHeader("Allow"), "Allow header must advertise POST");
        assertEquals("", McpHttpTestSupport.bodyOf(response), "405 returns before writing a body");
        // No runtime assertion here can detect a regression to response.sendError(): on this
        // mocklet, sendError(int) just delegates to setStatus(int) and sendRedirect(String) is a
        // complete no-op, so a sendError()-based 405 would report the exact same status and the
        // exact same absent Location header as the correct setStatus()-based implementation. That
        // regression is guarded instead by SendErrorProhibitedTest, which scans the production
        // sources for any use of response.sendError(...).
    }

    @Test
    public void testNotificationWithoutMetaOrHeadersGets202() throws Exception {
        // A conformant notification carries neither _meta nor the metadata headers.
        // If the notification check ran after _meta validation this would be a 400.
        final String body = post(new TestManager(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}", Map.of());
        assertEquals(202, lastResponse.getStatus());
        assertEquals("", body, "202 must have no body");
    }

    @Test
    public void testMalformedJsonIs400WithParseErrorAndNoId() throws Exception {
        final String body = post(new TestManager(), "{not json", Map.of());
        assertEquals(400, lastResponse.getStatus());
        assertTrue(body.contains("-32700"), body);
        assertFalse(body.contains("\"id\""), "an unknown id must be omitted, not null: " + body);
    }

    @Test
    public void testExplicitNullIdIs32600() throws Exception {
        final String body = post(new TestManager(), "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}", Map.of());
        assertEquals(400, lastResponse.getStatus());
        assertTrue(body.contains("-32600"), body);
        assertFalse(body.contains("\"id\""), body);
    }

    @Test
    public void testMissingHeaderIsHeaderMismatchNotUnsupportedVersion() throws Exception {
        // The body version MUST be one this server does NOT support. With a supported
        // version the test is vacuous: reordering the pipeline so the version is validated
        // before header presence would still yield -32020, because the version check would
        // pass. Task 6's review proved this against the equivalent unit test.
        final String body =
                post(new TestManager(), modernBody("tools/list").replace("\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"",
                        "\"io.modelcontextprotocol/protocolVersion\":\"2025-06-18\""), Map.of());
        assertEquals(400, lastResponse.getStatus());
        assertTrue(body.contains("-32020"),
                "a missing required header is -32020, not -32022, even when the body version is unsupported: " + body);
    }

    @Test
    public void testHeaderPresenceIsCheckedBeforeVersionSupport() throws Exception {
        // The ordering guarantee itself. HeaderValidator.requirePresent and requireMatches
        // have no shared caller of their own, so only process() can prove the order.
        final String body =
                post(new TestManager(),
                        modernBody("tools/list").replace("\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"",
                                "\"io.modelcontextprotocol/protocolVersion\":\"1900-01-01\""),
                        Map.of(McpConstants.HEADER_METHOD, "tools/list"));
        // MCP-Protocol-Version header absent AND body version unsupported: the transport
        // directs -32020 for the missing header, not -32022 for the version.
        assertTrue(body.contains("-32020"), "presence must be validated before version support: " + body);
        assertFalse(body.contains("-32022"), body);
    }

    @Test
    public void testUnsupportedVersionCarriesSupportedAndRequested() throws Exception {
        final String body = post(new TestManager(),
                modernBody("tools/list").replace("\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"",
                        "\"io.modelcontextprotocol/protocolVersion\":\"2025-06-18\""),
                Map.of(McpConstants.HEADER_PROTOCOL_VERSION, "2025-06-18", McpConstants.HEADER_METHOD, "tools/list"));
        assertEquals(400, lastResponse.getStatus());
        assertTrue(body.contains("-32022"), body);
        assertTrue(body.contains("\"supported\""), body);
        assertTrue(body.contains("\"requested\":\"2025-06-18\""), "the -32022 payload requires both fields: " + body);
    }

    @Test
    public void testUnknownMethodIs404() throws Exception {
        final String body = post(new TestManager(), modernBody("no/such/method"), modernHeaders("no/such/method"));
        assertEquals(404, lastResponse.getStatus(), "MCP 2026-07-28 mandates 404 for an unknown method");
        assertTrue(body.contains("-32601"), body);
    }

    @Test
    public void testInitializeNamesTheSupportedVersion() throws Exception {
        final String body = post(new TestManager(), modernBody("initialize"), modernHeaders("initialize"));
        assertEquals(404, lastResponse.getStatus());
        assertTrue(body.contains("2026-07-28"), "legacy clients need this diagnostic: " + body);
    }

    @Test
    public void testLoggingSetLevelIsMethodNotFound() throws Exception {
        // Regression: the logging capability and logging/setLevel handler were removed
        // (MCP 2026-07-28 deprecates logging). Migrated from the retired
        // McpApiManagerTest#testDispatchRpcMethod_LoggingSetLevel_IsMethodNotFound.
        final String body = post(new TestManager(), modernBody("logging/setLevel"), modernHeaders("logging/setLevel"));
        assertEquals(404, lastResponse.getStatus());
        assertTrue(body.contains("-32601"), body);
    }

    @Test
    public void testBatchIsRejected() throws Exception {
        // A JSON array body never reaches McpRequest.parse (Request-object validation, -32600):
        // Json.parseObject rejects the array shape first, as a parse error (-32700). Pinning the
        // exact code here, not just the HTTP status, is what keeps README.md's Error Codes table
        // (which attributes this case to -32700, not -32600) honest against a future regression.
        final String body = post(new TestManager(), "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]", Map.of());
        assertEquals(400, lastResponse.getStatus(), "batching was removed in 2025-06-18");
        assertFalse(body.isEmpty());
        assertTrue(body.contains("\"code\":-32700"), "a JSON array body is a parse error (-32700), not -32600: " + body);
    }

    @Test
    public void testSuccessPathWritesResultWithMatchingIdAndServerInfo() throws Exception {
        // Walking every other case in this file: 405 and the disabled-endpoint 503 return before
        // readBoundedRequestBody; the notification path returns before dispatch; every 400/404
        // case throws before dispatch. That leaves process()'s success branch --
        // writer.writeResult(response, id, getDispatcher().dispatch(context)), and the
        // McpCallContext construction just above it -- exercised by zero tests. This is the one
        // that does. The real handlers need a DI container (AbstractCacheableHandler#getFessConfig),
        // so getDispatcher() is stubbed with a single fake McpMethodHandler instead.
        final TestManager manager = new TestManager() {
            @Override
            protected McpDispatcher getDispatcher() {
                return new McpDispatcher(List.of(new McpMethodHandler() {
                    @Override
                    public String getMethod() {
                        return "tools/list";
                    }

                    @Override
                    public Map<String, Object> handle(final McpCallContext context) {
                        return new LinkedHashMap<>(Map.of("tools", List.of()));
                    }
                }));
            }
        };
        final String body = post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(200, lastResponse.getStatus(), body);
        assertTrue(body.contains("\"id\":1"), "the response id must echo the request id: " + body);
        assertTrue(body.contains("\"result\""), body);
        assertTrue(body.contains("io.modelcontextprotocol/serverInfo"), "writeResult must stamp serverInfo: " + body);
    }

    @Test
    public void testHeaderPresenceCheckedBeforeMetaExtraction() throws Exception {
        // Step 10 (HeaderValidator.requirePresent) must run before step 11
        // (McpRequestMeta.parse): a body with no _meta and no headers must fail as a missing
        // header (-32020), not as a missing _meta (-32602). If requirePresent and
        // McpRequestMeta.parse were swapped, this would be -32602 instead.
        final String body = post(new TestManager(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", Map.of());
        assertEquals(400, lastResponse.getStatus());
        assertTrue(body.contains("-32020"), "a missing header must be reported before a missing _meta: " + body);
        assertFalse(body.contains("-32602"), body);
    }

    @Test
    public void testApplicationLevelErrorReturnsHttp200WithJsonRpcError() throws Exception {
        // An application-level failure (unknown tool) must surface as HTTP 200 with a JSON-RPC
        // error body, not as an HTTP error status: only transport-level failures use non-200
        // statuses in this pipeline.
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"unknown_tool\",\"arguments\":{}}}";
        final Map<String, String> headers = Map.of(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28", McpConstants.HEADER_METHOD,
                "tools/call", McpConstants.HEADER_NAME, "unknown_tool");
        final String responseBody = post(new TestManager(), body, headers);
        assertEquals(200, lastResponse.getStatus(), responseBody);
        assertTrue(responseBody.contains("-32602"), responseBody);
    }

    @Test
    public void testOversizedBodyIs413() throws Exception {
        final TestManager manager = new TestManager();
        manager.maxBytes = 10;
        final String body = post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(413, lastResponse.getStatus(), body);
    }

    @Test
    public void testForeignOriginIs403() throws Exception {
        // Wiring check: OriginValidatorTest covers the matching rules directly; this confirms
        // McpApiManager#process actually calls validateOrigin(request) ahead of the rest of the
        // pipeline, with no id echoed since the failure precedes body parsing.
        final TestManager manager = new TestManager();
        final Map<String, String> headers = new LinkedHashMap<>(modernHeaders("tools/list"));
        headers.put("Origin", "https://evil.example.com");
        final String body = post(manager, modernBody("tools/list"), headers);
        assertEquals(403, lastResponse.getStatus(), body);
        assertTrue(body.contains("\"jsonrpc\":\"2.0\""), body);
        assertTrue(body.contains("-32600"), body);
        assertFalse(body.contains("\"id\""), "an unknown id must be omitted, not null: " + body);
        assertTrue(manager.allowedOriginsCalled, "a present Origin must consult the allowed-origins config");
    }

    @Test
    public void testAbsentOriginSkipsAllowedOriginsLookup() throws Exception {
        // validateOrigin must check for the Origin header itself before calling
        // getAllowedOrigins() -- that call reaches ComponentUtil.getFessConfig() in production,
        // so paying for it on every request (including the CLI-bridge/stdio-proxy majority that
        // never sends Origin) would be pointless container traffic on the hot path that
        // authenticate is about to join at this same call site.
        final TestManager manager = new TestManager();
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(200, lastResponse.getStatus());
        assertFalse(manager.allowedOriginsCalled, "getAllowedOrigins() must not run when Origin is absent");
    }

    /** A {@code tools/call} body/header pair naming a tool this server does not register. */
    private static final String UNKNOWN_TOOL_CALL_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"_meta\":{"
            + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"unknown_tool\",\"arguments\":{}}}";

    private Map<String, String> unknownToolCallHeaders() {
        return Map.of(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28", McpConstants.HEADER_METHOD, "tools/call",
                McpConstants.HEADER_NAME, "unknown_tool");
    }

    @Test
    public void testNonRateLimitedMethodSkipsRateLimiterLookup() throws Exception {
        // enforceRateLimit must check the resolved method before calling getRateLimiter() --
        // that call reaches ComponentUtil.getFessConfig() in production on its first invocation,
        // so paying for it on every request (including server/discover and the list methods,
        // which the spec does not rate-limit) would be pointless container traffic.
        final TestManager manager = new TestManager();
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(200, lastResponse.getStatus());
        assertFalse(manager.rateLimiterCalled, "getRateLimiter() must not run for a method the rate limit does not cover");
    }

    @Test
    public void testToolsCallConsultsRateLimiter() throws Exception {
        // Positive control for testNonRateLimitedMethodSkipsRateLimiterLookup: proves the flag
        // is not simply always false, only false for methods the limit does not cover.
        final TestManager manager = new TestManager();
        post(manager, UNKNOWN_TOOL_CALL_BODY, unknownToolCallHeaders());
        assertTrue(manager.rateLimiterCalled, "tools/call must consult the rate limiter");
    }

    @Test
    public void testCompletionCompleteConsultsRateLimiter() throws Exception {
        final TestManager manager = new TestManager();
        post(manager, modernBody("completion/complete"), modernHeaders("completion/complete"));
        assertTrue(manager.rateLimiterCalled, "completion/complete must consult the rate limiter");
    }

    @Test
    public void testRateLimitExceededIs429WithRetryAfterHeader() throws Exception {
        final TestManager manager = new TestManager();
        manager.rateLimiter = new RateLimiter(1);
        final String first = post(manager, UNKNOWN_TOOL_CALL_BODY, unknownToolCallHeaders());
        assertEquals(200, lastResponse.getStatus(), "the first call is within the limit: " + first);

        final String second = post(manager, UNKNOWN_TOOL_CALL_BODY, unknownToolCallHeaders());
        assertEquals(429, lastResponse.getStatus(), second);
        assertEquals("60", lastResponse.getHeader("Retry-After"), second);
        assertTrue(second.contains("\"id\":1"), "the id is known by the time the rate limit is enforced: " + second);
        assertTrue(second.contains("-32603"), second);
    }

    @Test
    public void testDisabledEndpointReturns503NotARedirect() throws Exception {
        final TestManager manager = new TestManager();
        manager.enabled = false;
        final String body = post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(503, lastResponse.getStatus(), "matches()==false would have produced a 302 + HTML");
        // The requirement is 503 + a JSON-RPC error body, not just a bare 503 status.
        assertTrue(body.contains("\"jsonrpc\":\"2.0\""), body);
        assertTrue(body.contains("\"error\""), body);
    }

    @Test
    public void testUncaughtExceptionBecomesAJsonRpcError() throws Exception {
        final TestManager manager = new TestManager() {
            @Override
            protected String readRequestBody(final HttpServletRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        final String body = post(manager, "", modernHeaders("tools/list"));
        assertTrue(body.contains("-32603"), "an escaping exception would become a container 500; it must be converted here: " + body);
    }

    @Test
    public void testMatchesIsExact() {
        final TestManager manager = new TestManager();
        assertTrue(manager.matches(McpHttpTestSupport.newRequest("POST", "/mcp")));
        assertTrue(manager.matches(McpHttpTestSupport.newRequest("POST", "/mcp/x")));
        assertFalse(manager.matches(McpHttpTestSupport.newRequest("POST", "/mcpfoo")), "startsWith would have matched this");
    }

    // NOTE: do NOT assert assertNull(response.getHeader("Location")) as a sendError guard.
    // MockletHttpServletResponseImpl.sendRedirect() is a no-op and sendError(int) delegates
    // straight to setStatus(int), so that assertion can never fail regardless of what
    // production does. The real guard is the source-scanning test added in Task 2
    // (SendErrorProhibitedTest); do not re-introduce a runtime Location assertion anywhere.
}
