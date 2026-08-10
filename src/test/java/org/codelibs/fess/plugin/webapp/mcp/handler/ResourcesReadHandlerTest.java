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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link ResourcesReadHandler}.
 */
public class ResourcesReadHandlerTest {

    private static final class FixedTtlHandler extends ResourcesReadHandler {

        @Override
        protected long getTtlMs() {
            return 0L;
        }
    }

    private final ResourcesReadHandler handler = new FixedTtlHandler();

    private McpCallContext contextWithUri(final String uri) {
        return new McpCallContext(null, null, uri == null ? Map.of() : Map.of("uri", uri));
    }

    @Test
    public void testGetMethod() {
        assertEquals("resources/read", handler.getMethod());
    }

    @Test
    public void testMissingUriIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri(null)));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testBlankUriIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("   ")));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testUnknownSchemeIsInvalidParamsNotEmptyContents() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("http://example.com/resource")));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(200, error.getHttpStatus(), "not-found is an application-level error, HTTP 200");
    }

    @Test
    public void testPartialUriIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("fess://index")));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testCaseMismatchUriIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("FESS://INDEX/STATS")));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testExtraPathSegmentIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("fess://index/stats/extra")));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testDocumentUriEmptyDocIdDoesNotMatchTheTemplate() {
        // fess://document/ has no [A-Za-z0-9_-]{1,256} after the slash, so the strict template
        // regex never matches -- this must not fall through to buildDocumentResource("").
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("fess://document/")));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testDocumentUriWithPathTraversalDoesNotMatchTheTemplate() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("fess://document/../../etc/passwd")));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testIndexStatsUriRequiresDiContainer() {
        // Past the URI-shape checks, buildIndexStatsResource() reaches ComponentUtil and, absent
        // a container, throws -- proving the routing itself, not the container-dependent content.
        assertThrows(IllegalStateException.class, () -> handler.handle(contextWithUri("fess://index/stats")));
    }

    @Test
    public void testDocumentUriRequiresDiContainer() {
        assertThrows(IllegalStateException.class, () -> handler.handle(contextWithUri("fess://document/doc1")));
    }

    @Test
    public void testCacheScopeIsAlwaysPrivate() {
        // getCacheScope is protected on AbstractCacheableHandler; this test class is in the same
        // package, so it can call the inherited method directly without needing a successful
        // (DI-container-backed) handle() call to observe it.
        assertEquals("private", handler.getCacheScope(contextWithUri("fess://index/stats")));
    }

    @Test
    public void testTtlDefaultsToZero() {
        // resources/read's own default (unlike the other five cacheable handlers) is 0, not
        // 3600000 -- reads are not cached by default.
        assertEquals(0L, ResourcesReadHandler.DEFAULT_TTL_MS);
    }

    @Test
    public void testTtlIsClampedToZeroWhenNegative() {
        // A not-found path never reaches putCacheHints, so it cannot prove the clamp is applied
        // there; buildIndexStatsResource is stubbed so handle() takes the real success path.
        final ResourcesReadHandler negative = new ResourcesReadHandler() {
            @Override
            protected long getTtlMs() {
                return -1L;
            }

            @Override
            protected Map<String, Object> buildIndexStatsResource() {
                final Map<String, Object> stub = new LinkedHashMap<>();
                stub.put("contents", List.of(Map.of("uri", STATS_URI)));
                return stub;
            }
        };

        final Map<String, Object> result = negative.handle(contextWithUri("fess://index/stats"));

        assertEquals(0L, result.get("ttlMs"), "the spec requires ttlMs >= 0");
        assertEquals("private", result.get("cacheScope"));
    }

    @Test
    public void testUnknownResourceMessageNamesTheUri() {
        // Never an empty "contents" array for a resource that does not exist: it must fail with
        // a message identifying the requested URI instead of silently succeeding with nothing.
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithUri("fess://unknown/thing")));
        assertTrue(error.getMessage().contains("fess://unknown/thing"));
    }
}
