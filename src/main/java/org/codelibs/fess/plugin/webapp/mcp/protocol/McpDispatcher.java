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
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler;

/**
 * Routes a parsed MCP call to the {@link McpMethodHandler} registered for its method.
 *
 * <p>
 * Not wired into {@link org.codelibs.fess.plugin.webapp.api.mcp.McpApiManager#process} yet: this
 * class exists so the per-method handlers have a single, tested entry point, but the HTTP
 * boundary still runs its own {@code dispatchRpcMethod} switch until a later task replaces it
 * with this dispatcher.
 * </p>
 */
public class McpDispatcher {

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
        if ("initialize".equals(method)) {
            // Legacy clients have no fall-forward mechanism; this error may be the only
            // diagnostic a user ever sees, so name the versions we do speak.
            throw new McpError(HttpServletResponse.SC_NOT_FOUND, ErrorCode.MethodNotFound,
                    "initialize was removed in MCP 2026-07-28; this server speaks " + McpConstants.PROTOCOL_VERSION,
                    Map.of("supportedVersions", List.copyOf(McpConstants.SUPPORTED_PROTOCOL_VERSIONS)));
        }
        throw new McpError(HttpServletResponse.SC_NOT_FOUND, ErrorCode.MethodNotFound, "Unknown method: " + method);
    }
}
