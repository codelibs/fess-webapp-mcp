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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.junit.jupiter.api.Test;

public class McpRequestTest {

    private Map<String, Object> envelope(final Object... kv) {
        final Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    public void testAbsentIdIsANotification() {
        final McpRequest request = McpRequest.parse(envelope("jsonrpc", "2.0", "method", "notifications/cancelled"));
        assertFalse(request.hasId(), "an absent id key means notification");
        assertTrue(request.isNotification());
    }

    @Test
    public void testExplicitNullIdIsNotANotification() {
        final Map<String, Object> map = envelope("jsonrpc", "2.0", "method", "tools/list");
        map.put("id", null);
        final McpError error = assertThrows(McpError.class, () -> McpRequest.parse(map));
        assertEquals(ErrorCode.InvalidRequest, error.getErrorCode(), "an explicit null id is an invalid Request object");
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    public void testNumericIdIsPreserved() {
        final McpRequest request = McpRequest.parse(envelope("jsonrpc", "2.0", "method", "tools/list", "id", 7));
        assertTrue(request.hasId());
        assertEquals(7, request.getId());
        assertFalse(request.isNotification());
    }

    @Test
    public void testWrongJsonrpcVersionIsRejected() {
        final McpError error =
                assertThrows(McpError.class, () -> McpRequest.parse(envelope("jsonrpc", "1.0", "method", "tools/list", "id", 1)));
        assertEquals(ErrorCode.InvalidRequest, error.getErrorCode());
    }

    @Test
    public void testMetaRequiresProtocolVersionAndCapabilities() {
        final Map<String, Object> params = new HashMap<>();
        params.put("_meta", envelope(McpConstants.META_PROTOCOL_VERSION, "2026-07-28"));
        final McpError error = assertThrows(McpError.class, () -> McpRequestMeta.parse(params));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode(), "clientCapabilities is required");
        assertEquals(400, error.getHttpStatus(), "a malformed _meta must be a 400");
    }

    @Test
    public void testMetaIsParsed() {
        final Map<String, Object> params = new HashMap<>();
        params.put("_meta",
                envelope(McpConstants.META_PROTOCOL_VERSION, "2026-07-28", McpConstants.META_CLIENT_CAPABILITIES, new HashMap<>()));
        final McpRequestMeta meta = McpRequestMeta.parse(params);
        assertEquals("2026-07-28", meta.getProtocolVersion());
    }
}
