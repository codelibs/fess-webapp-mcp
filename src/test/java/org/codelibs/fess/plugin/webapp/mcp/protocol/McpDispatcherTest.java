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
        // ping was removed outright in 2026-07-28 (not even special-cased like initialize).
        final McpDispatcher dispatcher = new McpDispatcher(List.of());

        final McpError error = assertThrows(McpError.class, () -> dispatcher.dispatch(contextFor("ping")));

        assertEquals(ErrorCode.MethodNotFound, error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }
}
