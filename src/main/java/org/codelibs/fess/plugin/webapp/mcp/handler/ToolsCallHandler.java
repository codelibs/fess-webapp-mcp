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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.auth.PermissionGate;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;

/**
 * The {@code tools/call} handler.
 *
 * <p>
 * Not a {@code CacheableResult}: {@code CallToolResult} is explicitly excluded from the schema's
 * cacheable-result union, so this result never carries {@code ttlMs} or {@code cacheScope}.
 * </p>
 *
 * <h2>What a failing tool is allowed to tell the caller</h2>
 * <p>
 * A tool failure reaches the caller through one of two shapes, and neither may echo an
 * exception message this plugin did not author:
 * </p>
 * <ul>
 *   <li><b>Deliberate, caller-directed failures are passed through verbatim.</b> An
 *       {@link McpApiException} carrying a request-side JSON-RPC code (-32700/-32600/-32601/
 *       -32602) is a tool telling the caller what is wrong with the caller's own request --
 *       {@code "Missing required parameter: doc_id"}, {@code "Missing required parameter: q"}.
 *       Those strings are written here, contain nothing but the argument name, and are the
 *       whole point of the error, so they are bridged to {@link McpError} unchanged.</li>
 *   <li><b>Everything else is replaced with a correlation id.</b> An {@code InternalError}
 *       (-32603) or an exception that escaped a tool entirely is describing a *server-side*
 *       failure, and its message routinely embeds text this plugin never wrote. Two verified
 *       examples: {@code IndexStatsTool} builds its message as
 *       {@code "Failed to serialize index stats: " + e.getMessage()}, and Fess's
 *       {@code SearchEngineClient} throws {@code InvalidQueryException("Invalid query: " +
 *       searchRequestBuilder)}, whose {@code toString()} is the fully serialized OpenSearch
 *       DSL -- including the role/permission filter terms that were already merged into it at
 *       throw time. A caller can trigger that one with nothing but a {@code start} between
 *       {@code index.max_result_window} and {@code query.max.search.result.offset}. The caller
 *       gets a fixed message plus {@code error_code:<uuid>}; the real message and stack trace
 *       go to the WARN log under the same id.</li>
 * </ul>
 * <p>
 * The redaction is unconditional -- it deliberately does not consult Fess's
 * {@code api.json.response.exception.included} flag. That flag is read through
 * {@code ComponentUtil}, which this container-free handler otherwise never touches, and it is
 * not an established contract for this shape of leak anyway: Fess's own v2 {@code SearchHandler}
 * leaks the same {@code InvalidQueryException} text without consulting it. Redacting always is
 * both cheaper and safer than making disclosure a configuration mistake away.
 * </p>
 * <p>
 * This leak is pre-existing rather than introduced here: the same {@code "Error: " +
 * e.getMessage()} lived in {@code McpApiManager} before this handler was extracted, and was
 * relocated unchanged. It is fixed here because this is where that code now lives.
 * </p>
 */
public class ToolsCallHandler implements McpMethodHandler {

    private static final Logger logger = LogManager.getLogger(ToolsCallHandler.class);

    /** The tools this handler can invoke. */
    private final List<McpTool> tools;

    /**
     * Creates a {@code tools/call} handler backed by this server's standard tool set.
     */
    public ToolsCallHandler() {
        this(McpTool.defaultTools());
    }

    /**
     * Creates a {@code tools/call} handler backed by an explicit tool set.
     *
     * @param tools the tools this handler can invoke
     */
    public ToolsCallHandler(final List<McpTool> tools) {
        this.tools = tools;
    }

    @Override
    public String getMethod() {
        return "tools/call";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(final McpCallContext context) {
        final Map<String, Object> params = context.getParams();

        final Object nameObj = params.get("name");
        final String name = nameObj instanceof String ? (String) nameObj : null;
        if (name == null || name.isEmpty()) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required parameter: name");
        }

        // params.arguments is optional, per the schema: CallToolRequestParams is
        // { name: string; arguments?: { [key: string]: unknown } }, with no "?" on name and one
        // on arguments (2026-07-28 schema.ts; it was already optional in 2024-11-05). Refusing
        // the call with -32602 when it was absent made the zero-argument call shape unusable:
        // get_index_stats declares {"type":"object","properties":{}} and takes no arguments at
        // all, so a conformant client that omitted the key -- the correct thing to send -- could
        // never reach it. The sibling PromptsGetHandler already defaults the identically-optional
        // GetPromptRequestParams.arguments? to Map.of(), and this mirrors it.
        //
        // This weakens no required-argument check. Which arguments a tool needs is the tool's own
        // business and is enforced inside McpTool#call: with an empty map, {"name":"search"} is
        // still refused as -32602 "Missing required parameter: q" by SearchTool#validateArguments.
        // What changes is only who reports it, and with which message.
        //
        // A non-Map arguments is defaulted the same way rather than rejected separately, again
        // mirroring PromptsGetHandler: it is a shape no client sends, the tools' own validation
        // still refuses whatever it actually needs, and a second differently-worded rejection
        // path would be one more thing to keep consistent for no caller's benefit.
        final Object argumentsObj = params.get("arguments");
        final Map<String, Object> arguments = argumentsObj instanceof Map ? (Map<String, Object>) argumentsObj : Map.of();

        final McpTool tool = findTool(name);
        // A single check, a single throw, and a single message for "no such tool" and "hidden by
        // the permission gate": an unauthorized caller must not be able to tell get_index_stats
        // apart from a tool that was never registered at all.
        if (tool == null || !PermissionGate.isAllowed(tool.getRequiredPermissions(), context.getPrincipal())) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Unknown tool: " + name);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Invoking tool: name={}, arguments={}", name, arguments);
        }

        try {
            // Defensive copy: McpResponseWriter#writeResult mutates the result via putIfAbsent,
            // but several McpTool implementations still return an immutable Map.of(...).
            return new LinkedHashMap<>(tool.call(arguments, context));
        } catch (final McpApiException e) {
            // McpTool#call still throws the pre-2026-07-28 exception type (Task 7 scope). Bridge
            // it into the handler-level McpError contract -- same ErrorCode, HTTP 200 because
            // this is an application-level failure -- without changing the tool itself.
            if (isCallerDirected(e.getCode())) {
                throw new McpError(HttpServletResponse.SC_OK, e.getCode(), e.getMessage());
            }
            // A server-side failure the tool chose to report itself. Its message is not
            // guaranteed to be self-authored (IndexStatsTool concatenates a Jackson message into
            // it), so it is redacted exactly like the catch-all below.
            final String errorId = newErrorId();
            logger.warn("[MCP] Tool '{}' reported an internal failure (error_code:{}): {}", name, errorId, e.getMessage(), e);
            throw new McpError(HttpServletResponse.SC_OK, e.getCode(), failureText(errorId));
        } catch (final McpError e) {
            // McpError is the go-forward contract and the tools' argument validation already
            // throws it (SearchTool/GetDocumentTool/SuggestTool reject a wrong-typed argument
            // this way). Letting it fall into the catch-all below would silently convert a
            // deliberate protocol error into an isError:true CallToolResult. Propagate it
            // unchanged instead.
            //
            // Not redacted: an McpError is only ever constructed by this plugin's own code, with
            // a message this plugin wrote, so it is caller-directed by construction -- unlike
            // the arbitrary backend exceptions the catch-all below has to assume the worst about.
            throw e;
        } catch (final Exception e) {
            // Unexpected: whatever this is, its message was written by code outside this plugin
            // and may embed backend internals (an OpenSearch DSL with role filters, a JVM cast
            // message naming loaded classes, a file path). The caller gets the correlation id
            // only; the operator gets the real message and stack trace at WARN under that id.
            final String errorId = newErrorId();
            logger.warn("[MCP] Tool '{}' execution failed (error_code:{}): {}", name, errorId, e.getMessage(), e);
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", "Error: " + failureText(errorId))));
            result.put("isError", true);
            return result;
        }
    }

    /**
     * Tells whether a JSON-RPC code means "the caller's request was wrong" rather than "this
     * server failed".
     * <p>
     * Only the request-side codes qualify: a tool raising one of them is describing the caller's
     * own arguments, so its message is safe to return verbatim. Everything else -- including
     * {@code InternalError}, the MCP-specific transport codes, and a null code -- is treated as
     * a server-side failure whose message must be redacted. Deliberately fail-closed: a code
     * added to {@link ErrorCode} later is redacted until someone decides otherwise.
     * </p>
     *
     * @param code the JSON-RPC code the tool raised, may be null
     * @return true when the accompanying message may be returned to the caller unchanged
     */
    protected static boolean isCallerDirected(final ErrorCode code) {
        if (code == null) {
            return false;
        }
        return switch (code) {
        case ParseError, InvalidRequest, MethodNotFound, InvalidParams -> true;
        default -> false;
        };
    }

    /**
     * Returns a fresh correlation id for one failure.
     * <p>
     * Mirrors Fess's own {@code FessApiFailureHook}, which emits {@code error_code:<uuid>} to the
     * client and logs the real cause under the same id. A new id per failure is what makes a
     * report ("I got error_code:X") pinpoint one log line.
     * </p>
     *
     * @return a random correlation id
     */
    protected static String newErrorId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Builds the caller-visible replacement for a redacted failure message.
     *
     * @param errorId the correlation id, as returned by {@link #newErrorId()}
     * @return a fixed message carrying nothing but the correlation id
     */
    protected static String failureText(final String errorId) {
        return "Tool execution failed (error_code:" + errorId + ")";
    }

    /**
     * Finds a registered tool by name.
     *
     * @param name the tool name
     * @return the matching tool, or {@code null} when no registered tool has that name
     */
    protected McpTool findTool(final String name) {
        for (final McpTool tool : tools) {
            if (tool.getName().equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
