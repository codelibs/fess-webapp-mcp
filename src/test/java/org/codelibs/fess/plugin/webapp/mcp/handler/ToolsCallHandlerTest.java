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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link ToolsCallHandler}.
 */
public class ToolsCallHandlerTest {

    private static class StubTool implements McpTool {

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "echo";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public Map<String, Object> getAnnotations() {
            return Map.of();
        }

        @Override
        public Set<String> getRequiredPermissions() {
            return Set.of();
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            return Map.of("content", List.of(Map.of("type", "text", "text", "ok:" + arguments.get("x"))));
        }
    }

    private static final class ThrowingApiExceptionTool extends StubTool {

        @Override
        public String getName() {
            return "bad_args";
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            throw new McpApiException(ErrorCode.InvalidParams, "bad argument");
        }
    }

    private static final class ThrowingRuntimeTool extends StubTool {

        @Override
        public String getName() {
            return "flaky";
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            throw new IllegalStateException("container not available");
        }
    }

    private static final class ThrowingMcpErrorTool extends StubTool {

        @Override
        public String getName() {
            return "already_modern";
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            // No shipped McpTool does this today (Task 7 scope predates McpError), but McpError
            // is the go-forward contract; a future/replacement tool may throw it directly.
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "already an McpError");
        }
    }

    private McpCallContext contextWithParams(final Map<String, Object> params) {
        return new McpCallContext(null, null, params);
    }

    @Test
    public void testGetMethod() {
        assertEquals("tools/call", new ToolsCallHandler(List.of()).getMethod());
    }

    @Test
    public void testMissingNameIsInvalidParams() {
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of("arguments", Map.of()))));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("name"));
    }

    @Test
    public void testMissingArgumentsIsInvalidParams() {
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of("name", "echo"))));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertTrue(error.getMessage().contains("arguments"));
    }

    @Test
    public void testUnknownToolIsInvalidParamsNotIsError() {
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final McpError error = assertThrows(McpError.class,
                () -> handler.handle(contextWithParams(Map.of("name", "no_such_tool", "arguments", Map.of()))));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        // Per the schema: finding-the-tool errors are MCP errors, not isError:true CallToolResults.
        assertEquals(200, error.getHttpStatus());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testInvokesTheMatchingToolAndReturnsItsResult() {
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "echo", "arguments", Map.of("x", "hi"))));
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals("ok:hi", content.get(0).get("text"));
    }

    @Test
    public void testToolLevelMcpApiExceptionIsBridgedToMcpError() {
        // McpTool#call still throws the pre-2026-07-28 McpApiException (Task 7 scope); this
        // handler bridges it into the handler-level McpError contract without touching the tool.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new ThrowingApiExceptionTool()));
        final McpError error =
                assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of("name", "bad_args", "arguments", Map.of()))));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(200, error.getHttpStatus());
        assertEquals("bad argument", error.getMessage());
    }

    @Test
    public void testToolLevelMcpErrorPropagatesUnchangedNotAsIsErrorResult() {
        // McpError extends RuntimeException, so without a dedicated catch clause ahead of the
        // generic Exception catch-all it would be silently converted into an isError:true
        // CallToolResult instead of propagating as the protocol error it already is.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new ThrowingMcpErrorTool()));

        final McpError error = assertThrows(McpError.class,
                () -> handler.handle(contextWithParams(Map.of("name", "already_modern", "arguments", Map.of()))));

        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(200, error.getHttpStatus());
        assertEquals("already an McpError", error.getMessage());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUnexpectedToolExceptionBecomesIsErrorResultNotAnMcpError() {
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new ThrowingRuntimeTool()));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "flaky", "arguments", Map.of())));
        assertEquals(true, result.get("isError"));
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertTrue(((String) content.get(0).get("text")).startsWith("Error:"));
    }

    @Test
    public void testResultIsMutableEvenWhenTheToolReturnsAnImmutableMap() {
        // McpResponseWriter#writeResult calls putIfAbsent on whatever is returned; several
        // existing McpTool implementations still return Map.of(...) for their success path.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "echo", "arguments", Map.of("x", "hi"))));
        assertDoesNotThrow(() -> result.put("resultType", "complete"));
    }

    @Test
    public void testDoesNotSetResultTypeOrServerInfo() {
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "echo", "arguments", Map.of("x", "hi"))));
        assertFalse(result.containsKey("resultType"));
        assertFalse(result.containsKey("_meta"));
    }

    @Test
    public void testDoesNotSetTtlMsOrCacheScope() {
        // CallToolResult is explicitly not a CacheableResult in the 2026-07-28 schema.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "echo", "arguments", Map.of("x", "hi"))));
        assertFalse(result.containsKey("ttlMs"));
        assertFalse(result.containsKey("cacheScope"));
    }
}
