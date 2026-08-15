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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link McpDispatcher}.
 */
public class McpDispatcherTest {

    /** A trivial handler that echoes a fixed body, for wiring tests only. */
    private static final class FixedHandler implements McpMethodHandler {

        private final String method;
        private final Map<String, Object> body;

        FixedHandler(final String method, final Map<String, Object> body) {
            this.method = method;
            this.body = body;
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public Map<String, Object> handle(final McpCallContext context) {
            return body;
        }
    }

    private McpCallContext contextFor(final String method) {
        final Map<String, Object> envelope = new HashMap<>();
        envelope.put("jsonrpc", McpConstants.JSONRPC_VERSION);
        envelope.put("method", method);
        envelope.put("id", 1);
        envelope.put("params", Map.of());
        final McpRequest request = McpRequest.parse(envelope);
        return new McpCallContext(request, null, request.getParams());
    }

    @Test
    public void testDispatchRoutesToTheRegisteredHandler() {
        final Map<String, Object> body = Map.of("tools", List.of());
        final McpDispatcher dispatcher = new McpDispatcher(List.of(new FixedHandler("tools/list", body)));

        final Map<String, Object> result = dispatcher.dispatch(contextFor("tools/list"));

        assertEquals(body, result);
    }

    @Test
    public void testDispatchPicksTheHandlerMatchingTheMethodNotTheFirstOne() {
        final McpDispatcher dispatcher = new McpDispatcher(List.of(new FixedHandler("tools/list", Map.of("which", "tools")),
                new FixedHandler("prompts/list", Map.of("which", "prompts"))));

        assertEquals("prompts", dispatcher.dispatch(contextFor("prompts/list")).get("which"));
        assertEquals("tools", dispatcher.dispatch(contextFor("tools/list")).get("which"));
    }

    @Test
    public void testUnknownMethodIs404MethodNotFound() {
        final McpDispatcher dispatcher = new McpDispatcher(List.of());

        final McpError error = assertThrows(McpError.class, () -> dispatcher.dispatch(contextFor("no/such/method")));

        assertEquals(404, error.getHttpStatus());
        assertEquals(ErrorCode.MethodNotFound, error.getErrorCode());
        assertTrue(error.getMessage().contains("no/such/method"));
    }

    @Test
    public void testInitializeIsSpecialCasedWithSupportedVersionInMessageAndData() {
        // initialize was removed from the 2026-07-28 schema entirely; it is not a registered
        // handler. Unlike a generic unknown method, legacy clients that still send it have no
        // fall-forward mechanism, so the diagnostic must name the version(s) this server speaks.
        final McpDispatcher dispatcher = new McpDispatcher(List.of());

        final McpError error = assertThrows(McpError.class, () -> dispatcher.dispatch(contextFor("initialize")));

        assertEquals(404, error.getHttpStatus());
        assertEquals(ErrorCode.MethodNotFound, error.getErrorCode());
        assertTrue(error.getMessage().contains(McpConstants.PROTOCOL_VERSION),
                "message should name the supported version for legacy clients with no fallback");
        assertEquals(List.copyOf(McpConstants.SUPPORTED_PROTOCOL_VERSIONS), error.getData().get("supportedVersions"));
    }

    @Test
    public void testPingIsMethodNotFoundNotSpecialCased() {
        // ping was removed outright in 2026-07-28 (not even special-cased like initialize):
        // README documents it as gone with no replacement, so there is no version to fall
        // forward to and nothing a supportedVersions payload could usefully tell that caller.
        final McpDispatcher dispatcher = new McpDispatcher(List.of());

        final McpError error = assertThrows(McpError.class, () -> dispatcher.dispatch(contextFor("ping")));

        assertEquals(ErrorCode.MethodNotFound, error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertNull(error.getData(), "the supportedVersions payload is initialize's alone");
    }

    // ------------------------------------------------------------------
    // Retired-method recognition. McpApiManager#process consults this to answer initialize/ping
    // BEFORE HeaderValidator.requirePresent -- a client old enough to still send them sends
    // neither MCP-Protocol-Version nor Mcp-Method, so without that short-circuit the diagnostic
    // above is unreachable by exactly the clients it was written for.
    // ------------------------------------------------------------------

    @Test
    public void testRetiredMethodsAreRecognised() {
        final McpDispatcher dispatcher = new McpDispatcher(List.of(new FixedHandler("tools/list", Map.of())));

        assertTrue(dispatcher.isRetired("initialize"), "initialize must be answerable before header validation");
        assertTrue(dispatcher.isRetired("ping"), "ping must be answerable before header validation");
        assertFalse(dispatcher.isRetired("tools/list"), "a live method must go through the full pipeline");
        assertFalse(dispatcher.isRetired("no/such/method"),
                "a merely unknown method must NOT skip header validation: its caller is a modern client that got the "
                        + "method name wrong, and -32020 for a missing header is the correct answer for one");
    }

    @Test
    public void testARegisteredHandlerBeatsTheRetiredList() {
        // Fail-safe for the short-circuit: if a future revision brings one of these back as a
        // real method, wiring the handler in must be the only change needed. A registration-blind
        // membership test would keep answering 404 and silently shadow the new handler.
        final McpDispatcher dispatcher = new McpDispatcher(List.of(new FixedHandler("ping", Map.of("pong", Boolean.TRUE))));

        assertFalse(dispatcher.isRetired("ping"));
        assertEquals(Boolean.TRUE, dispatcher.dispatch(contextFor("ping")).get("pong"));
    }

    @Test
    public void testMethodNotFoundIsTheSharedDefinitionOfTheDiagnostic() {
        // McpApiManager throws this static directly for a retired method rather than duplicating
        // the message and payload at its own call site, so the two paths cannot answer the same
        // method differently. Pinning it here is what makes that sharing meaningful.
        final McpError initialize = McpDispatcher.methodNotFound(McpDispatcher.METHOD_INITIALIZE);
        assertEquals(404, initialize.getHttpStatus());
        assertEquals(ErrorCode.MethodNotFound, initialize.getErrorCode());
        assertTrue(initialize.getMessage().contains(McpConstants.PROTOCOL_VERSION), initialize.getMessage());
        assertEquals(List.copyOf(McpConstants.SUPPORTED_PROTOCOL_VERSIONS), initialize.getData().get("supportedVersions"));

        final McpError ping = McpDispatcher.methodNotFound(McpDispatcher.METHOD_PING);
        assertEquals(404, ping.getHttpStatus());
        assertEquals(ErrorCode.MethodNotFound, ping.getErrorCode());
        assertNull(ping.getData());
    }

    @Test
    public void testPingSaysItWasRemovedRatherThanMerelyUnknown() {
        // The whole point of answering the retired methods before header validation is that the
        // caller is told the method is gone instead of being sent off to add a header. ping got
        // the generic "Unknown method" text, which delivers neither. The supportedVersions
        // payload stays initialize's alone -- that decision is unchanged; only the wording is.
        final McpDispatcher dispatcher = new McpDispatcher(List.of());

        final McpError error = assertThrows(McpError.class, () -> dispatcher.dispatch(contextFor("ping")));

        assertTrue(error.getMessage().contains("removed in MCP 2026-07-28"),
                "ping's diagnostic must say the method is gone, was: " + error.getMessage());
        assertNull(error.getData(), "the supportedVersions payload is still initialize's alone");
    }

    @Test
    public void testAGenuinelyUnknownMethodStillGetsTheGenericText() {
        // Guards the fix from over-reaching: only the two retired methods get the removal
        // wording. A typo'd method name must not be described as removed in this revision.
        final McpDispatcher dispatcher = new McpDispatcher(List.of());

        final McpError error = assertThrows(McpError.class, () -> dispatcher.dispatch(contextFor("tools/lst")));

        assertEquals("Unknown method: tools/lst", error.getMessage(), "an unrecognised method is unknown, not retired");
    }
}
