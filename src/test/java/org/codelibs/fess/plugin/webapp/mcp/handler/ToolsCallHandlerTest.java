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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    /**
     * A tool whose failure carries backend internals in its message, the way Fess's
     * {@code SearchEngineClient} does: it throws
     * {@code InvalidQueryException("Invalid query: " + searchRequestBuilder)}, and
     * {@code SearchRequestBuilder#toString()} is the serialized OpenSearch DSL -- with the
     * role/permission filter already merged into it. A caller reaches that with nothing more
     * exotic than a {@code start} between {@code index.max_result_window} and
     * {@code query.max.search.result.offset}.
     */
    private static final class LeakingTool extends StubTool {

        /** An abridged copy of a real serialized request, including the role filter terms. */
        static final String LEAKY_MESSAGE =
                "Invalid query: {\"from\":50000,\"size\":3,\"query\":{\"bool\":{\"filter\":[{\"bool\":{\"must_not\":"
                        + "[{\"term\":{\"role\":{\"value\":\"1guest\"}}}]}}]}}}";

        @Override
        public String getName() {
            return "leaky";
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            throw new IllegalStateException(LEAKY_MESSAGE);
        }
    }

    /**
     * A tool that reports a server-side failure through the legacy exception type, the way
     * {@code IndexStatsTool} does: it builds its message as
     * {@code "Failed to serialize index stats: " + e.getMessage()}, so the text is a backend
     * (Jackson) message wearing this plugin's prefix.
     */
    private static final class ThrowingInternalApiExceptionTool extends StubTool {

        /** The shape of IndexStatsTool's InternalError message. */
        static final String LEAKY_MESSAGE = "Failed to serialize index stats: no serializer found for class /var/lib/fess/secret";

        @Override
        public String getName() {
            return "internal_failure";
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            throw new McpApiException(ErrorCode.InternalError, LEAKY_MESSAGE);
        }
    }

    private static final class ThrowingMcpErrorTool extends StubTool {

        @Override
        public String getName() {
            return "already_modern";
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            // McpError is the go-forward contract, and the shipped tools' argument validation
            // already throws it directly (SearchTool/GetDocumentTool/SuggestTool reject a
            // wrong-typed argument this way).
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
        // The message survives verbatim on purpose: -32602 from a tool means "your arguments are
        // wrong", and that text is written by this plugin (e.g. "Missing required parameter:
        // doc_id"), so redacting it would destroy the only useful part of the error.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new ThrowingApiExceptionTool()));
        final McpError error =
                assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of("name", "bad_args", "arguments", Map.of()))));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(200, error.getHttpStatus());
        assertEquals("bad argument", error.getMessage());
    }

    @Test
    public void testToolLevelInternalErrorMessageIsRedactedToACorrelationId() {
        // The other half of the rule the test above pins: a tool reporting -32603 is describing
        // a server-side failure, not the caller's request, and its message is routinely a
        // backend's own text with a prefix bolted on (IndexStatsTool concatenates a Jackson
        // message). McpResponseWriter puts McpError#getMessage() straight into the wire body, so
        // this must not reach the caller. The code and HTTP status are unchanged -- only the text.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new ThrowingInternalApiExceptionTool()));

        final McpError error = assertThrows(McpError.class,
                () -> handler.handle(contextWithParams(Map.of("name", "internal_failure", "arguments", Map.of()))));

        assertEquals(ErrorCode.InternalError, error.getErrorCode(), "the JSON-RPC code must survive redaction");
        assertEquals(200, error.getHttpStatus(), "an application-level failure stays HTTP 200");
        assertFalse(
                error.getMessage().contains("Jackson") || error.getMessage().contains("no serializer")
                        || error.getMessage().contains("/var/lib/fess"),
                "the backend message must not reach the caller: " + error.getMessage());
        assertCarriesCorrelationId(error.getMessage());
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
    public void testUnexpectedToolExceptionTextIsRedactedToACorrelationId() {
        // The envelope is unchanged (isError:true at HTTP 200, asserted above); what changes is
        // that the text no longer echoes the exception message. Fess's SearchEngineClient puts
        // the whole serialized OpenSearch DSL -- role filter included -- into an
        // InvalidQueryException message that propagates here untouched, so echoing it hands an
        // unauthenticated caller both the query internals and the permission terms.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new LeakingTool()));

        final String text = errorTextOf(handler.handle(contextWithParams(Map.of("name", "leaky", "arguments", Map.of()))));

        assertFalse(text.contains("role"), "the role/permission filter must not reach the caller: " + text);
        assertFalse(text.contains("1guest"), "the role/permission filter must not reach the caller: " + text);
        assertFalse(text.contains("Invalid query"), "the backend message must not reach the caller: " + text);
        assertFalse(text.contains("50000"), "the serialized DSL must not reach the caller: " + text);
        assertCarriesCorrelationId(text);
    }

    @Test
    public void testEachFailureGetsItsOwnCorrelationId() {
        // The id is only useful for pointing an operator at one log line, so it must be per
        // failure, not a constant.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new ThrowingRuntimeTool()));
        final Map<String, Object> params = Map.of("name", "flaky", "arguments", Map.of());

        final String first = correlationIdOf(errorTextOf(handler.handle(contextWithParams(params))));
        final String second = correlationIdOf(errorTextOf(handler.handle(contextWithParams(params))));

        assertNotEquals(first, second, "two failures must be distinguishable in the log");
    }

    /**
     * Returns the text of the first content block of an {@code isError:true} tool result.
     *
     * @param result the {@code CallToolResult} map the handler returned
     * @return the first content block's text
     */
    @SuppressWarnings("unchecked")
    private String errorTextOf(final Map<String, Object> result) {
        assertEquals(true, result.get("isError"), "a failed tool call must still be reported as isError:true");
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return (String) content.get(0).get("text");
    }

    /**
     * Asserts that {@code message} carries an {@code error_code:<uuid>} correlation id, without
     * which an operator cannot map a caller's complaint onto the WARN log line holding the real
     * cause.
     *
     * @param message the caller-visible message
     */
    private void assertCarriesCorrelationId(final String message) {
        assertTrue(message.contains("error_code:"), "the caller needs a correlation id to quote: " + message);
        final String id = correlationIdOf(message);
        assertDoesNotThrow(() -> UUID.fromString(id), "the correlation id must be a UUID, was: " + id);
    }

    /**
     * Extracts the {@code error_code:} value from a caller-visible message.
     *
     * @param message the caller-visible message
     * @return the correlation id, with the trailing {@code ")"} stripped
     */
    private String correlationIdOf(final String message) {
        final String tail = message.substring(message.indexOf("error_code:") + "error_code:".length());
        final int end = tail.indexOf(')');
        return end < 0 ? tail.trim() : tail.substring(0, end).trim();
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
