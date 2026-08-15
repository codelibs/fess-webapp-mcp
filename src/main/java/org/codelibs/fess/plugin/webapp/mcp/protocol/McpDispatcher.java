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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler;

/**
 * Routes a parsed MCP call to the {@link McpMethodHandler} registered for its method.
 *
 * <p>
 * Wired into {@code McpApiManager#process}, which builds one dispatcher instance from the nine
 * shipped handlers and routes every validated call through it, replacing the legacy
 * {@code dispatchRpcMethod} switch that lived on {@code McpApiManager} before this class existed.
 * </p>
 */
public class McpDispatcher {

    /** The retired handshake method; the only one whose diagnostic names the versions this server speaks. */
    public static final String METHOD_INITIALIZE = "initialize";

    /** The retired liveness-check method. */
    public static final String METHOD_PING = "ping";

    /**
     * The methods MCP 2026-07-28 removed outright, which a pre-2026-07-28 client will still
     * send.
     * <p>
     * Membership here is <em>not</em> about the shape of the error -- an unregistered method is
     * a {@code -32601} either way. It is about <em>reachability</em>: {@code McpApiManager}
     * consults {@link #isRetired(String)} to answer these two before
     * {@code HeaderValidator.requirePresent} runs, because a client old enough to still call
     * them is by definition one that sends neither {@code MCP-Protocol-Version} nor
     * {@code Mcp-Method} (the latter did not exist before this revision). Without that
     * short-circuit every legacy caller gets {@code -32020 "MCP-Protocol-Version is required"},
     * which sends its operator off adding a header rather than telling them the method is gone
     * -- and the diagnostic below, written precisely for those clients, would be unreachable by
     * them.
     * </p>
     */
    private static final Set<String> RETIRED_METHODS = Set.of(METHOD_INITIALIZE, METHOD_PING);

    private final Map<String, McpMethodHandler> handlersByMethod;

    /**
     * Creates a dispatcher that routes by each handler's {@link McpMethodHandler#getMethod()}.
     *
     * @param handlers the handlers to register; when two handlers declare the same method, the
     *            later one in the list wins
     */
    public McpDispatcher(final List<McpMethodHandler> handlers) {
        final Map<String, McpMethodHandler> map = new LinkedHashMap<>();
        for (final McpMethodHandler handler : handlers) {
            map.put(handler.getMethod(), handler);
        }
        this.handlersByMethod = map;
    }

    /**
     * Routes a request to its handler.
     *
     * @param context the call context
     * @return the handler's result body
     * @throws McpError with HTTP 404 and -32601 when the method is unknown
     */
    public Map<String, Object> dispatch(final McpCallContext context) {
        final String method = context.getRequest().getMethod();
        final McpMethodHandler handler = handlersByMethod.get(method);
        if (handler != null) {
            return handler.handle(context);
        }
        throw methodNotFound(method);
    }

    /**
     * Reports whether {@code method} is one this dispatcher would answer with the retired-method
     * diagnostic, so {@code McpApiManager} can emit it before header validation runs.
     * <p>
     * A registered handler always wins: the check is deliberately registration-aware rather than
     * a bare {@link #RETIRED_METHODS} membership test, so that if a future revision ever brings
     * {@code ping} (or {@code initialize}) back as a real handler, wiring that handler in is the
     * only change needed -- the caller's short-circuit stops firing on its own instead of
     * silently shadowing the new handler with a 404.
     * </p>
     *
     * @param method the JSON-RPC method name from the parsed body
     * @return true when {@code method} was retired in MCP 2026-07-28 and no handler claims it
     */
    public boolean isRetired(final String method) {
        return RETIRED_METHODS.contains(method) && !handlersByMethod.containsKey(method);
    }

    /**
     * Builds the {@code -32601} failure for a method no handler claims.
     * <p>
     * The single definition of that error, shared by {@link #dispatch}'s fall-through and by
     * {@code McpApiManager}'s pre-header-validation short-circuit for {@link #isRetired retired}
     * methods, so the two call sites cannot drift into answering the same method differently.
     * </p>
     * <p>
     * {@link #METHOD_INITIALIZE} alone carries {@code data.supportedVersions}: legacy clients
     * have no fall-forward mechanism, so this error may be the only diagnostic their user ever
     * sees, and naming the version this server does speak is the one thing that error can
     * usefully say. {@link #METHOD_PING} deliberately does <em>not</em> get that payload --
     * README documents it as gone outright, with no replacement and no version to fall forward
     * to, so there is nothing for a {@code supportedVersions} list to tell that caller.
     * </p>
     *
     * @param method the JSON-RPC method name
     * @return the error to throw; never null
     */
    public static McpError methodNotFound(final String method) {
        if (METHOD_INITIALIZE.equals(method)) {
            return new McpError(HttpServletResponse.SC_NOT_FOUND, ErrorCode.MethodNotFound,
                    "initialize was removed in MCP 2026-07-28; this server speaks " + McpConstants.PROTOCOL_VERSION,
                    Map.of("supportedVersions", List.copyOf(McpConstants.SUPPORTED_PROTOCOL_VERSIONS)));
        }
        return new McpError(HttpServletResponse.SC_NOT_FOUND, ErrorCode.MethodNotFound, "Unknown method: " + method);
    }
}
