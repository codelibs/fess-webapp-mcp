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

import java.util.Map;

import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;

/**
 * A single JSON-RPC method handler for the MCP 2026-07-28 endpoint.
 *
 * <p>
 * Before this interface existed, every method's logic lived as one more {@code handle*} method
 * on the god-class {@code McpApiManager}. Each implementation now owns exactly one method's
 * request handling, so {@link org.codelibs.fess.plugin.webapp.mcp.protocol.McpDispatcher} can
 * route by {@link #getMethod()} instead of a growing {@code switch}.
 * </p>
 */
public interface McpMethodHandler {

    /**
     * Returns the JSON-RPC method name this handler answers, e.g. {@code "server/discover"}.
     *
     * @return the method name
     */
    String getMethod();

    /**
     * Handles one call to {@link #getMethod()}.
     *
     * @param context the per-invocation call context
     * @return the result body; the writer adds {@code resultType} and {@code _meta.serverInfo}
     * @throws McpError with HTTP 200 and {@code -32602} for an invalid argument or a not-found
     *             resource/prompt/tool, or with HTTP 200 and {@code -32603} for an internal
     *             error raised while handling the method
     */
    Map<String, Object> handle(McpCallContext context);
}
