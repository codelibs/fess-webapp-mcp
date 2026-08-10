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
package org.codelibs.fess.plugin.webapp.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link DiscoverHandler}.
 *
 * <p>
 * The brief's own sample test double subclasses {@code DiscoverHandler} via
 * {@code super(List.of())}, but {@code handle()} never reads a tool/handler list -- capabilities
 * are fixed empty objects and {@code supportedVersions} comes straight from
 * {@link McpConstants}. Carrying an unused constructor parameter would be speculative, so
 * {@link DiscoverHandler} takes none; see task-8-report.md for the full reasoning.
 * </p>
 */
public class DiscoverHandlerTest {

    /** Test double that avoids the DI container by fixing the TTL directly. */
    static class TestDiscoverHandler extends DiscoverHandler {

        @Override
        protected long getTtlMs() {
            return 3600000L;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetMethod() {
        assertEquals("server/discover", new TestDiscoverHandler().getMethod());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDiscoverResultShape() {
        final Map<String, Object> result = new TestDiscoverHandler().handle(null);

        assertEquals(List.of(McpConstants.PROTOCOL_VERSION), result.get("supportedVersions"));
        assertEquals(3600000L, result.get("ttlMs"), "DiscoverResult extends CacheableResult");
        assertEquals("public", result.get("cacheScope"));

        final Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
        assertNotNull(capabilities);
        assertTrue(capabilities.containsKey("tools"));
        assertTrue(capabilities.containsKey("resources"));
        assertTrue(capabilities.containsKey("prompts"));
        assertTrue(capabilities.containsKey("completions"));
        assertFalse(capabilities.containsKey("logging"), "logging is deprecated in 2026-07-28");

        final Map<String, Object> toolsCap = (Map<String, Object>) capabilities.get("tools");
        assertTrue(toolsCap.isEmpty(), "tools capability must be an empty object");
        assertFalse(toolsCap.containsKey("listChanged"), "advertising listChanged would oblige us to implement subscriptions/listen");

        final Map<String, Object> resourcesCap = (Map<String, Object>) capabilities.get("resources");
        assertFalse(resourcesCap.containsKey("subscribe"), "advertising subscribe would oblige us to implement subscriptions/listen");
        assertFalse(resourcesCap.containsKey("listChanged"));

        assertNotNull(result.get("instructions"));
        assertTrue(((String) result.get("instructions")).contains("search"), "instructions should mention the search tool");
    }

    @Test
    public void testTtlIsClampedToZero() {
        final DiscoverHandler handler = new DiscoverHandler() {
            @Override
            protected long getTtlMs() {
                return -1L;
            }
        };
        assertEquals(0L, handler.handle(null).get("ttlMs"), "the spec requires ttlMs >= 0");
    }

    @Test
    public void testDoesNotSetResultTypeOrServerInfo() {
        // resultType and _meta.serverInfo are McpResponseWriter's job (putIfAbsent). A handler
        // that set either would duplicate that responsibility and could mask a writer regression.
        final Map<String, Object> result = new TestDiscoverHandler().handle(null);
        assertFalse(result.containsKey("resultType"));
        assertFalse(result.containsKey("_meta"));
    }

    @Test
    public void testResultIsMutable() {
        // McpResponseWriter#writeResult calls putIfAbsent on the result the handler returns;
        // an immutable Map.of(...) would throw UnsupportedOperationException there.
        final Map<String, Object> result = new TestDiscoverHandler().handle(null);
        assertDoesNotThrow(() -> result.put("resultType", "complete"));
    }

    @Test
    public void testResolveServerVersion_FallsBackToUnknownOutsideAJar() {
        // Running from target/test-classes (not a packaged jar) has no manifest, so
        // Package#getImplementationVersion() returns null and the fallback applies.
        final TestDiscoverHandler handler = new TestDiscoverHandler();
        assertEquals("unknown", handler.resolveServerVersion());
    }
}
