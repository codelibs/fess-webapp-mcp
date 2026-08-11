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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.RateLimiter;
import org.codelibs.fess.plugin.webapp.mcp.auth.OAuthResourceServerAuthenticator;
import org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpDispatcher;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP-boundary tests for McpApiManager. These run without a DI container,
 * so every config read and the body read must go through a protected seam.
 */
public class McpApiManagerHttpTest {

    /** Test double: supplies a canned body and never touches ComponentUtil. */
    static class TestManager extends McpApiManager {
        /**
         * The canned body, or {@code null} to let {@code McpApiManager#readRequestBody}'s real
         * (bounded) body run against the request's own input stream -- see
         * {@link McpApiManagerHttpTest#postStream}.
         */
        String body = "";
        boolean enabled = true;
        int maxBytes = 1_048_576;
        boolean allowedOriginsCalled = false;

        @Override
        protected String readRequestBody(final HttpServletRequest request) throws IOException {
            // A canned body skips the real read entirely, which is what every test in this file
            // that is not about the read itself wants (the mocklet request has no usable input
            // stream). A null body opts back in to the production read, so the bounded-read
            // tests exercise it for real rather than asserting against this stub.
            return body != null ? body : super.readRequestBody(request);
        }

        @Override
        protected void writeHeaders(final HttpServletResponse response) {
            // no-op: the real implementation reads api.json.response.headers from the container
        }

        @Override
        protected String resolveClientIp(final HttpServletRequest request) {
            // The real implementation delegates to Fess's RateLimitHelper via ComponentUtil,
            // which needs a live DI container; this suite is container-free by design. Falling
            // back to getRemoteAddr() keeps the key-resolution behaviour these tests actually
            // exercise (subject-when-authenticated, IP otherwise) while skipping the container.
            // The trusted-proxy logic itself is Fess's, and is covered by Fess's own tests.
            return request.getRemoteAddr();
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

    /**
     * Posts a request whose body arrives through a real {@link ServletInputStream}, so
     * {@code McpApiManager#readRequestBody}'s production body -- the bounded read -- actually
     * runs.
     * <p>
     * {@code MockletHttpServletRequestImpl#getInputStream()} throws
     * {@code UnsupportedOperationException}, which is why every other test in this file supplies
     * a canned body through the seam instead; wrapping it is the only way to hand the production
     * read something to read. The response is still built from the underlying mocklet request,
     * since that is what {@code MockletHttpServletResponseImpl} expects.
     * </p>
     *
     * @param manager the manager under test; its canned body is cleared so the real read runs
     * @param in the body stream
     * @param headers the request headers
     * @return the response body
     * @throws Exception if process() throws
     */
    private String postStream(final TestManager manager, final ServletInputStream in, final Map<String, String> headers) throws Exception {
        manager.body = null;
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        headers.forEach(request::addHeader);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        manager.process(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                return in;
            }
        }, response, null);
        lastResponse = response;
        return McpHttpTestSupport.bodyOf(response);
    }

    /**
     * An endless request body: every read hands back another {@code 'x'}, and the stream fails
     * the test outright once more than {@code failAfter} bytes have been handed out.
     * <p>
     * This is what makes "the limit is enforced without buffering the whole body" falsifiable. A
     * regression to {@code readAllBytes()} against this stream cannot quietly succeed: it either
     * trips the guard here or never terminates, and either way the 413 assertion fails. A finite
     * fixture could not distinguish the two implementations at all -- both would end up with the
     * same bytes and the same status.
     * </p>
     */
    private static final class EndlessBody extends ServletInputStream {
        private final long failAfter;
        long delivered;

        EndlessBody(final long failAfter) {
            this.failAfter = failAfter;
        }

        @Override
        public int read() {
            if (++delivered > failAfter) {
                throw new AssertionError("the body read must stop after " + failAfter + " bytes, but it asked for byte " + delivered
                        + " -- the limit is being applied after buffering the whole body, not while reading it");
            }
            return 'x';
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            throw new UnsupportedOperationException();
        }
    }

    /** A finite request body of exactly the given bytes. */
    private static final class FixedBody extends ServletInputStream {
        private final byte[] data;
        private int position;

        FixedBody(final byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xff : -1;
        }

        @Override
        public boolean isFinished() {
            return position >= data.length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            throw new UnsupportedOperationException();
        }
    }

    private Map<String, String> modernHeaders(final String method) {
        return Map.of(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28", McpConstants.HEADER_METHOD, method);
    }

    private String modernBody(final String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"," + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
    }

    /**
     * A {@link #modernBody} carrying an extra {@code params.pad} string, placed at the very end
     * of the JSON so that any truncation of the read bytes shows up as a parse failure.
     *
     * @param method the JSON-RPC method name
     * @param pad the padding text
     * @return the request body
     */
    private String paddedBody(final String method, final String pad) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}},\"pad\":\"" + pad + "\"}}";
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

    // ------------------------------------------------------------------
    // Retired methods (initialize, ping). The diagnostic McpDispatcher raises for these exists
    // for pre-2026-07-28 clients, so it has to be reachable BY one -- which means before
    // HeaderValidator.requirePresent, since a client old enough to call initialize sends none of
    // the request-metadata headers. Asserting it with modernHeaders() only proves the case that
    // never needed proving.
    // ------------------------------------------------------------------

    /** A genuine legacy {@code initialize} call: the pre-2026-07-28 envelope, and no MCP headers at all. */
    private static final String LEGACY_INITIALIZE_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"legacy-client\",\"version\":\"1.0.0\"}}}";

    @Test
    public void testLegacyInitializeWithNoMcpHeadersGetsTheDiagnosticNotAHeaderError() throws Exception {
        final String body = post(new TestManager(), LEGACY_INITIALIZE_BODY, Map.of());

        assertEquals(404, lastResponse.getStatus(), body);
        assertTrue(body.contains("-32601"), body);
        assertFalse(body.contains("-32020"),
                "a legacy client sends neither MCP-Protocol-Version nor Mcp-Method, so answering it with a header error "
                        + "sends its operator off adding headers instead of telling them initialize is gone: " + body);
        assertTrue(body.contains("supportedVersions"), "the diagnostic's whole point is naming what this server does speak: " + body);
        assertTrue(body.contains("2026-07-28"), body);
    }

    @Test
    public void testModernClientCallingInitializeGetsTheSameDiagnostic() throws Exception {
        // The other side of the same coin: short-circuiting the retired methods early must not
        // change what a conformant client sees. McpDispatcherTest covers the dispatcher's own
        // branch directly; this covers the pipeline answering with the identical payload.
        final String body = post(new TestManager(), modernBody("initialize"), modernHeaders("initialize"));

        assertEquals(404, lastResponse.getStatus(), body);
        assertTrue(body.contains("-32601"), body);
        assertTrue(body.contains("supportedVersions"), body);
        assertTrue(body.contains("2026-07-28"), body);
    }

    @Test
    public void testLegacyPingWithNoMcpHeadersIsMethodNotFoundNotAHeaderError() throws Exception {
        // ping was retired too, and a client still calling it is just as header-less. README
        // documents it as a plain -32601 with no replacement, so -- unlike initialize -- it
        // deliberately carries no supportedVersions payload: there is no version to fall
        // forward to that would bring ping back.
        final String body = post(new TestManager(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of());

        assertEquals(404, lastResponse.getStatus(), body);
        assertTrue(body.contains("-32601"), body);
        assertFalse(body.contains("-32020"), body);
        assertFalse(body.contains("supportedVersions"), "README documents supportedVersions for initialize only: " + body);
    }

    @Test
    public void testRetiredMethodSentAsANotificationIsStillAccepted() throws Exception {
        // Ordering guard for the short-circuit above: the notification check MUST stay ahead of
        // it. A notification carries no id, no _meta and no headers, and the transport leaves
        // header requirements for notification POSTs undefined -- answering one with a 404
        // instead of the 202 would be a protocol regression, and putting the retired-method
        // check first is exactly how that would happen.
        final String body = post(new TestManager(), "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}", Map.of());

        assertEquals(202, lastResponse.getStatus(), body);
        assertEquals("", body, "202 must have no body");
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

    // ------------------------------------------------------------------
    // The bounded body read. These drive McpApiManager#readRequestBody's REAL body against a
    // real ServletInputStream: the seam-based double every other test in this file uses would
    // make them vacuous, since it returns a String that was never read from anything.
    // ------------------------------------------------------------------

    @Test
    public void testOversizedBodyIs413WithoutBufferingTheWholeBody() throws Exception {
        // The point of the limit is that an attacker-chosen body size must not decide the
        // allocation -- this endpoint is reachable unauthenticated in the default
        // mcp.auth.mode=none, and this read happens before the rate limiter. Asserting the 413
        // alone would not prove that: reading everything and then measuring it also produces a
        // 413. EndlessBody is what closes the gap -- it fails the moment the read goes past
        // max + 1 bytes -- and the delivered-byte assertion pins the exact bound.
        final TestManager manager = new TestManager();
        manager.maxBytes = 64;
        final EndlessBody in = new EndlessBody(65);

        final String body = postStream(manager, in, modernHeaders("tools/list"));

        assertEquals(413, lastResponse.getStatus(), body);
        assertTrue(body.contains("-32600"), body);
        assertTrue(body.contains("mcp.request.max.bytes"), "the message must name the property an operator has to raise: " + body);
        assertEquals(65L, in.delivered,
                "the read must stop exactly one byte past the limit: fewer and a body of exactly max bytes could not be "
                        + "told apart from an oversized one, more and the limit is not bounding the allocation");
    }

    @Test
    public void testBodyOfExactlyTheLimitIsAcceptedAndTheLimitCountsBytesNotCharacters() throws Exception {
        // Two off-by-one hazards in one fixture. The body carries a multi-byte UTF-8 run, so its
        // byte length and its String length differ: an implementation that measured the decoded
        // String would let through up to three times the configured bytes.
        final String json = paddedBody("no/such/method", "日本語");
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertEquals(json.length() + 6, bytes.length, "the fixture must actually be multi-byte, or the rest of this test proves nothing");

        // Exactly the limit is within it, and the body reaches the dispatcher decoded intact --
        // a read that stopped short, or that decoded a max+1 probe buffer, would have truncated
        // the JSON and surfaced as a -32700 parse error instead of a -32601 method-not-found.
        final TestManager atLimit = new TestManager();
        atLimit.maxBytes = bytes.length;
        final String accepted = postStream(atLimit, new FixedBody(bytes), modernHeaders("no/such/method"));
        assertEquals(404, lastResponse.getStatus(), accepted);
        assertTrue(accepted.contains("-32601"), "a body of exactly max bytes must be read, decoded and dispatched: " + accepted);

        // One byte over the limit is over it. This is also the byte-vs-character guard: the
        // limit here is still six bytes ABOVE the body's String length, so an implementation
        // counting characters would wrongly accept it.
        final TestManager overLimit = new TestManager();
        overLimit.maxBytes = bytes.length - 1;
        final String rejected = postStream(overLimit, new FixedBody(bytes), modernHeaders("no/such/method"));
        assertEquals(413, lastResponse.getStatus(), rejected);
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
    public void testResourcesReadConsultsRateLimiter() throws Exception {
        // resources/read is not one of the two methods the spec names, but it is the same
        // backend work under a different name: fess://document/<id> makes the identical
        // SearchHelper#getDocumentByDocId call the rate-limited get_document tool makes, and
        // fess://index/stats runs a live cluster/JVM stats collection. Leaving it unlimited made
        // Mcp-Method: resources/read an unmetered, unauthenticated document-fetch channel that
        // simply routed around the tools/call limit.
        final TestManager manager = new TestManager();
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}},\"uri\":\"fess://document/abc123\"}}";
        final Map<String, String> headers = Map.of(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28", McpConstants.HEADER_METHOD,
                "resources/read", McpConstants.HEADER_NAME, "fess://document/abc123");

        post(manager, body, headers);

        assertTrue(manager.rateLimiterCalled, "resources/read must consult the rate limiter");
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

    // ------------------------------------------------------------------
    // I6: isEnabled()/getRequestMaxBytes()/getRateLimitPerMinute()'s real (non-overridden)
    // bodies. Every test double above overrides these methods wholesale, so neither their
    // literal system-property key nor their default value is ever actually executed by this
    // suite -- a typo in a key, or a flipped default, would pass all of them. Mirrors
    // AuthenticatorTest's SystemPropertyCapturingManager for McpApiManager#getAuthMode(): only
    // the lowest-level ComponentUtil-touching primitives are overridden, so the real bodies run.
    // ------------------------------------------------------------------

    /** Test double: overrides only {@code getSystemPropertyAsBoolean}/{@code getSystemPropertyAsInt}, not the higher-level methods. */
    static class SystemPropertyCapturingManager extends McpApiManager {
        String capturedBooleanKey;
        boolean capturedBooleanDefault;
        String capturedIntKey;
        int capturedIntDefault;

        @Override
        protected boolean getSystemPropertyAsBoolean(final String key, final boolean defaultValue) {
            // Simulates an unset property: real FessConfig#getSystemPropertyAsBoolean returns
            // defaultValue precisely when the key is unset, so echoing it back here is a
            // faithful stand-in without needing a live container.
            capturedBooleanKey = key;
            capturedBooleanDefault = defaultValue;
            return defaultValue;
        }

        @Override
        protected int getSystemPropertyAsInt(final String key, final int defaultValue) {
            capturedIntKey = key;
            capturedIntDefault = defaultValue;
            return defaultValue;
        }
    }

    @Test
    public void testIsEnabledRealBodyDefaultsToTrueWhenPropertyUnset() {
        final SystemPropertyCapturingManager manager = new SystemPropertyCapturingManager();

        final boolean enabled = manager.isEnabled();

        assertEquals("mcp.enabled", manager.capturedBooleanKey, "isEnabled() must read this exact property key");
        assertTrue(manager.capturedBooleanDefault,
                "isEnabled() must default to true, or a config typo would silently disable the endpoint everywhere");
        assertTrue(enabled);
    }

    @Test
    public void testGetRequestMaxBytesRealBodyReadsExpectedKeyAndDefault() {
        final SystemPropertyCapturingManager manager = new SystemPropertyCapturingManager();

        final int maxBytes = manager.getRequestMaxBytes();

        assertEquals("mcp.request.max.bytes", manager.capturedIntKey);
        assertEquals(1_048_576, manager.capturedIntDefault);
        assertEquals(1_048_576, maxBytes);
    }

    @Test
    public void testGetRateLimitPerMinuteRealBodyReadsExpectedKeyAndDefault() {
        final SystemPropertyCapturingManager manager = new SystemPropertyCapturingManager();

        final int perMinute = manager.getRateLimitPerMinute();

        assertEquals("mcp.rate.limit.per.minute", manager.capturedIntKey);
        assertEquals(60, manager.capturedIntDefault);
        assertEquals(60, perMinute);
    }

    // ------------------------------------------------------------------
    // I7: getRateLimiter() must cache the built instance on the field, or every request gets a
    // fresh, un-shared counter and rate limiting is silently disabled. The TestManager double
    // above overrides getRateLimiter() itself to return a pre-built field, so it never exercises
    // the real synchronized-lazy-init body this test targets.
    // ------------------------------------------------------------------

    /** Test double: overrides only getRateLimitPerMinute(), leaving getRateLimiter()'s real caching body to run. */
    static class RealRateLimiterManager extends McpApiManager {
        @Override
        protected int getRateLimitPerMinute() {
            return 5;
        }
    }

    @Test
    public void testGetRateLimiterCachesTheSameInstanceAcrossCalls() {
        final RealRateLimiterManager manager = new RealRateLimiterManager();

        assertSame(manager.getRateLimiter(), manager.getRateLimiter(),
                "getRateLimiter() must cache the built instance on the field -- returning a fresh RateLimiter per call would "
                        + "silently disable rate limiting, since every caller would always see an empty, unshared counter");
    }

    // ------------------------------------------------------------------
    // I8: the effective authenticator is re-resolved on EVERY request (getAuthenticator() reads
    // mcp.auth.mode and the mcp.oauth.* keys each time), and Fess's DynamicProperties re-reads
    // its backing file within seconds of an mtime change -- so a config edit can flip /mcp from
    // "401 for everyone" to "200 for anyone" with no restart. warnIfAuthenticationIsDisabled()
    // is only wired to @PostConstruct, so before this it happened in total silence.
    // ------------------------------------------------------------------

    /** Test double: records each reported authentication-posture change instead of logging it. */
    static class AuthStateRecordingManager extends TestManager {
        final List<String> reported = new ArrayList<>();

        @Override
        protected void logAuthState(final AuthState state, final String authMode, final AuthState previous) {
            reported.add(previous + "->" + state);
        }
    }

    @Test
    public void testRuntimeAuthModeChangeIsReportedOnceAndNotPerRequest() throws Exception {
        final AuthStateRecordingManager manager = new AuthStateRecordingManager();
        manager.authMode = McpApiManager.AUTH_MODE_NONE;

        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(List.of("null->NONE"), manager.reported, "the first resolution must be reported");

        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(List.of("null->NONE"), manager.reported,
                "an unchanged posture must never be re-reported: this endpoint is unauthenticated in exactly the mode "
                        + "being warned about, so a per-request WARN of several hundred bytes is a log-flood amplifier");

        // The hazard itself: mcp.auth.mode edited on a running server.
        manager.authMode = McpApiManager.AUTH_MODE_FESS_TOKEN;
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(List.of("null->NONE", "NONE->FESS_TOKEN"), manager.reported, "a runtime change of posture must be reported");

        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(List.of("null->NONE", "NONE->FESS_TOKEN"), manager.reported, "and then go quiet again in the new steady state");

        // The direction that actually matters: authentication silently switching itself off.
        manager.authMode = McpApiManager.AUTH_MODE_NONE;
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));
        assertEquals(List.of("null->NONE", "NONE->FESS_TOKEN", "FESS_TOKEN->NONE"), manager.reported,
                "/mcp dropping from 401-for-everyone to 200-for-anyone must not happen silently");
    }

    @Test
    public void testOauthFallingBackToNoneIsADistinctStateFromNone() throws Exception {
        // mcp.auth.mode=oauth with an incomplete configuration behaves exactly like none, so
        // collapsing it onto NONE would look like "no change" and report nothing -- yet it is
        // the single most important case to report, because the operator believes they turned
        // authentication ON. A separate state is what keeps that transition visible.
        final AuthStateRecordingManager manager = new AuthStateRecordingManager() {
            @Override
            protected OAuthResourceServerAuthenticator getOAuthAuthenticator() {
                // no-op: the real isUsable() reads mcp.oauth.issuer via ComponentUtil
                return new OAuthResourceServerAuthenticator() {
                    @Override
                    public boolean isUsable() {
                        return false;
                    }
                };
            }
        };
        manager.authMode = McpApiManager.AUTH_MODE_NONE;
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));

        manager.authMode = McpApiManager.AUTH_MODE_OAUTH;
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));

        assertEquals(List.of("null->NONE", "NONE->OAUTH_UNUSABLE"), manager.reported,
                "an unusable oauth configuration must be reported even though it behaves identically to none");
    }

    // NOTE: do NOT assert assertNull(response.getHeader("Location")) as a sendError guard.
    // MockletHttpServletResponseImpl.sendRedirect() is a no-op and sendError(int) delegates
    // straight to setStatus(int), so that assertion can never fail regardless of what
    // production does. The real guard is the source-scanning test added in Task 2
    // (SendErrorProhibitedTest); do not re-introduce a runtime Location assertion anywhere.
    @Test
    public void testRateLimitKeyComesFromTheClientIpSeamNotRemoteAddr() {
        // Behind nginx or Apache, getRemoteAddr() is the proxy's address for every caller, so
        // keying on it directly collapses all anonymous callers -- and mcp.auth.mode=none, the
        // default, makes every caller anonymous -- into a single bucket. One caller spending the
        // per-minute budget then 429s every other client of the same Fess instance. Fess ships no
        // RemoteIpValve, so that is the default deployment, not an edge case.
        //
        // The production seam delegates to Fess's RateLimitHelper, which honours X-Forwarded-For
        // and X-Real-IP only when the peer is listed in rate.limit.trusted.proxies. This pins that
        // resolveRateLimitKey really goes through that seam: reverting it to request.getRemoteAddr()
        // reintroduces the shared bucket, and nothing else in the suite would notice.
        final TestManager manager = new TestManager() {
            @Override
            protected String resolveClientIp(final HttpServletRequest request) {
                return "203.0.113.9";
            }
        };
        assertEquals("203.0.113.9", manager.resolveRateLimitKey(McpHttpTestSupport.newRequest("POST", "/mcp"), new McpCallContext()),
                "the rate-limit key must come from resolveClientIp, not getRemoteAddr");
    }

    @Test
    public void testRateLimitKeyFallsBackToUnknownWhenTheClientIpIsNull() {
        // RateLimiter is built on ConcurrentHashMap, which rejects null keys outright, so an
        // unresolvable IP must become a literal rather than propagate as null.
        final TestManager manager = new TestManager() {
            @Override
            protected String resolveClientIp(final HttpServletRequest request) {
                return null;
            }
        };
        assertEquals("unknown", manager.resolveRateLimitKey(McpHttpTestSupport.newRequest("POST", "/mcp"), new McpCallContext()),
                "a null client IP must not reach ConcurrentHashMap");
    }
}
