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

import org.codelibs.fess.exception.InvalidQueryException;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.IndexStatsTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.SearchTool;
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

    /**
     * The real {@code get_index_stats} tool with its two DI-backed seams substituted, so its
     * genuine {@code call()} -- schema-conforming {@code structuredContent} included -- can run
     * without a container.
     * <p>
     * The permission gate is opened the way {@code IndexStatsGateTest} opens it (a blank
     * {@code mcp.tools.index_stats.permissions}), because what is under test here is the
     * {@code arguments} handling, not the gate.
     * </p>
     */
    private static final class StubbedIndexStatsTool extends IndexStatsTool {

        @Override
        protected String getIndexStatsPermissions() {
            return "";
        }

        @Override
        public Map<String, Object> collectIndexStats() {
            return Map.of("index", Map.of("index_name", "fess.search", "document_count", Long.valueOf(7L)), "config",
                    Map.of("max_page_size", Integer.valueOf(100)), "system", Map.of("memory", Map.of("total_bytes", Long.valueOf(1L),
                            "free_bytes", Long.valueOf(1L), "used_bytes", Long.valueOf(0L), "max_bytes", Long.valueOf(2L))));
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
    public void testEmptyNameIsInvalidParams() {
        // The isEmpty() half of the name guard, which nothing exercised: the test above only
        // covers the absent/non-String half, so deleting {@code || name.isEmpty()} left the
        // suite green while {"name": ""} fell through to findTool("") and was reported as
        // "Unknown tool: " -- a different message, for what is really a malformed request.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final McpError error =
                assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of("name", "", "arguments", Map.of()))));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals("Missing required parameter: name", error.getMessage(),
                "an empty name is a missing name, not an unknown tool: " + error.getMessage());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMissingArgumentsIsAcceptedAsAnEmptyMap() {
        // Inverted from testMissingArgumentsIsInvalidParams, which pinned the opposite.
        //
        // params.arguments is OPTIONAL in the normative schema -- 2026-07-28
        // schema.ts declares CallToolRequestParams as { name: string; arguments?: {...} }, and it
        // was already optional in 2024-11-05 -- so refusing the call with -32602 made every
        // conformant client's zero-argument call fail. The sibling PromptsGetHandler already
        // handles the identically-optional GetPromptRequestParams.arguments? this way, with a
        // Map.of() fallback.
        //
        // "ok:null" rather than an exception is what proves the tool ran and was handed an empty
        // map: StubTool#call reads arguments.get("x") unconditionally, so a null map would NPE
        // here instead.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "echo")));
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals("ok:null", content.get(0).get("text"), "an absent arguments must reach the tool as an empty map");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testNonMapArgumentsIsAlsoTreatedAsAnEmptyMap() {
        // The other half of the instanceof check. A non-object arguments is malformed rather
        // than absent, but it is defaulted the same way PromptsGetHandler defaults it: the
        // alternative is a second, differently-worded rejection path for a shape no client
        // sends, and the tool's own validation still refuses the call when it actually needs an
        // argument (asserted below).
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubTool()));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "echo", "arguments", "not-an-object")));
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals("ok:null", content.get(0).get("text"), "a non-object arguments must reach the tool as an empty map");
    }

    @Test
    public void testMissingArgumentsDoesNotWeakenAToolsOwnRequiredArgumentCheck() {
        // The safety half of accepting an absent arguments: defaulting it to an empty map must
        // not turn a tool's required argument into an optional one. Uses the real SearchTool,
        // whose validateArguments is container-free, rather than a stub -- a stub would prove
        // only that a stub can still throw.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new SearchTool()));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of("name", "search"))));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(200, error.getHttpStatus());
        assertEquals("Missing required parameter: q", error.getMessage(), "search must still refuse a call with no q");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testZeroParameterToolRunsWithNoArgumentsKeyAtAll() {
        // Why the conformance gap mattered in practice: get_index_stats declares
        // {"type":"object","properties":{}} and takes no arguments at all, so omitting
        // params.arguments is its normal call shape -- and it was therefore unreachable for a
        // conformant client. This runs the real tool's real call() (only the two DI-backed seams
        // are substituted) to prove the whole path completes, not merely that the arguments
        // check was passed.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of(new StubbedIndexStatsTool()));

        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "get_index_stats")));

        assertFalse(result.containsKey("isError"), "a zero-argument tool must succeed, not report a failure: " + result);
        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final Map<String, Object> index = (Map<String, Object>) structured.get("index");
        assertEquals(Long.valueOf(7L), index.get("document_count"), "the real tool's own output must come back");
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

    /**
     * A tool that fails the way Fess fails a caller's query.
     */
    private static class RejectingTool extends StubTool {

        private final RuntimeException failure;

        RejectingTool(final RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String getName() {
            return "search";
        }

        @Override
        public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
            throw failure;
        }
    }

    /**
     * Resolves message codes without a container, standing in for Fess's MessageManager.
     */
    private ToolsCallHandler handlerRejectingWith(final InvalidQueryException failure, final String resolved) {
        return new ToolsCallHandler(List.of(new RejectingTool(failure))) {
            @Override
            protected String resolveCallerMessage(
                    final org.lastaflute.web.validation.VaMessenger<org.codelibs.fess.mylasta.action.FessMessages> messageCode) {
                return resolved;
            }
        };
    }

    private McpError reject(final InvalidQueryException failure, final String resolved) {
        final ToolsCallHandler handler = handlerRejectingWith(failure, resolved);
        return assertThrows(McpError.class,
                () -> handler.handle(contextWithParams(Map.of("name", "search", "arguments", Map.of("q", "x")))));
    }

    @Test
    public void testRejectedQueryIsAnInvalidParamsRatherThanACorrelationId() {
        // A caller who sent an unusable argument value has to be told which one, or an agent
        // retries the same call forever. This is the same shape the tools already use for a
        // wrong-typed argument, so the two cannot disagree.
        final McpError error = reject(new InvalidQueryException(messages -> {}, "Unsupported sort field: sort:nope.asc"),
                "The specified sort nope.asc is unsupported.");
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(HttpServletResponse.SC_OK, error.getHttpStatus());
        assertEquals("The specified sort nope.asc is unsupported.", error.getMessage());
        assertFalse(error.getMessage().contains("error_code:"), "a caller-directed failure needs no correlation id");
    }

    @Test
    public void testRejectedQueryNeverEchoesTheExceptionMessage() {
        // The SearchEngineClient variant carries the executed OpenSearch DSL, role filters
        // included. Only the resolved message code may reach the caller.
        final String dsl = "Failed query: {\"query\":{\"bool\":{\"filter\":[{\"term\":{\"role\":\"Rguest\"}}]}}}";
        final McpError error = reject(new InvalidQueryException(messages -> {}, dsl), "Could not process the specified query.");
        assertEquals("Could not process the specified query.", error.getMessage());
        assertFalse(error.getMessage().contains("Rguest"), "the caller's role filter must not leak");
        assertFalse(error.getMessage().contains("{"), "the executed DSL must not leak");
        assertFalse(error.getMessage().contains("bool"), "the executed DSL must not leak");
    }

    @Test
    public void testAnUnresolvableMessageCodeStillYieldsCallerFacingText() {
        // Whatever goes wrong resolving the bundle, the caller must not get an empty message
        // and must not get a 500.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of());
        assertEquals(ToolsCallHandler.FALLBACK_INVALID_QUERY, handler.resolveCallerMessage(null));
    }

    @Test
    public void testTheResolvedTextIsLocaleIndependent() {
        // Resolved against Locale.ROOT rather than the servlet request, so a Japanese-default
        // host does not answer one message in Japanese while the rest of this plugin's errors
        // stay English.
        final ToolsCallHandler handler = new ToolsCallHandler(List.of());
        final java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.JAPAN);
            assertEquals(ToolsCallHandler.FALLBACK_INVALID_QUERY, handler.resolveCallerMessage(null));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    public void testAResolverFailureFallsBackInsteadOfPropagating() {
        final ToolsCallHandler handler = new ToolsCallHandler(List.of()) {
            @Override
            protected String resolveCallerMessage(
                    final org.lastaflute.web.validation.VaMessenger<org.codelibs.fess.mylasta.action.FessMessages> messageCode) {
                // Exercise the real body, which has no container to resolve against here.
                return super.resolveCallerMessage(messageCode);
            }
        };
        assertEquals(ToolsCallHandler.FALLBACK_INVALID_QUERY, assertDoesNotThrow(() -> handler.resolveCallerMessage(messages -> {})));
    }

    @Test
    public void testAnUnexpectedFailureIsStillRedacted() {
        // The narrower arm must not have widened the caller-directed set: anything that is not
        // an InvalidQueryException keeps its correlation id.
        final ToolsCallHandler handler =
                new ToolsCallHandler(List.of(new RejectingTool(new IllegalStateException("org.opensearch.internal detail /var/lib/fess"))));
        final Map<String, Object> result = handler.handle(contextWithParams(Map.of("name", "search", "arguments", Map.of("q", "x"))));
        assertEquals(Boolean.TRUE, result.get("isError"));
        final String text = (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
        assertCarriesCorrelationId(text);
        assertFalse(text.contains("/var/lib/fess"), "an unexpected failure must stay redacted");
    }

}
