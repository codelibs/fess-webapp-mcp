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
package org.codelibs.fess.plugin.webapp.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;

/**
 * A single MCP tool: its identity, JSON Schema, annotations, permission requirement, and
 * behaviour, all owned by one implementation.
 *
 * <p>
 * Before this interface existed, a tool's schema lived in {@code McpApiManager#handleListTools}
 * while its behaviour lived in a {@code switch} inside {@code McpApiManager#handleInvoke} --
 * two places that had already drifted apart. Each {@code McpTool} implementation now owns both,
 * so renaming or adding an argument means editing one file.
 * </p>
 */
public interface McpTool {

    /**
     * Returns the tool name as it appears in {@code tools/list} and is dispatched on in
     * {@code tools/call}.
     *
     * @return the tool name; must match {@code [A-Za-z0-9_.-]\{1,128\}} per the MCP spec
     */
    String getName();

    /**
     * Returns the human-readable description shown to MCP clients in {@code tools/list}.
     *
     * @return the tool description
     */
    String getDescription();

    /**
     * Returns the JSON Schema describing the {@code arguments} this tool accepts.
     *
     * @return the input schema, always an object schema ({@code type: "object"})
     */
    Map<String, Object> getInputSchema();

    /**
     * Returns the JSON Schema describing this tool's {@code structuredContent}.
     * <p>
     * {@code structuredContent} is not populated by any tool yet -- that is a later task's
     * work. Until then, every implementation returns the minimal valid placeholder
     * {@code {"type": "object"}} rather than {@code null}: this method promises a non-null
     * schema (below), and an unconstrained-but-typed placeholder is safer for a future caller
     * to treat as "not yet meaningful" than either null (which the contract forbids) or a
     * schema shape that looks deliberate but isn't.
     * </p>
     *
     * @return the output schema; never null
     */
    Map<String, Object> getOutputSchema();

    /**
     * Returns the MCP tool annotations (hints such as {@code readOnlyHint}) describing this
     * tool's behavioural characteristics.
     *
     * @return the annotations map
     */
    Map<String, Object> getAnnotations();

    /**
     * Returns the encoded Fess permissions the caller must hold to invoke this tool.
     *
     * @return the required permissions; empty means the tool is not gated
     */
    Set<String> getRequiredPermissions();

    /**
     * Executes this tool.
     *
     * @param arguments the tool arguments, as parsed from the request's {@code params.arguments}
     * @param context the per-invocation call context
     * @return a {@code CallToolResult} body: {@code content}, optional {@code structuredContent},
     *         and optional {@code isError}
     */
    Map<String, Object> call(Map<String, Object> arguments, McpCallContext context);

    /**
     * Returns the standard tool set this server exposes by default.
     *
     * <p>
     * The single source of truth for "which tools does this server have, in what order" for the
     * {@code mcp.handler} package's {@code ToolsListHandler} and {@code ToolsCallHandler}, which
     * both need it and must not drift against each other. {@code McpApiManager} no longer keeps a
     * separate copy: the legacy {@code dispatchRpcMethod} switch and its {@code getTools()} were
     * retired when {@code McpApiManager#process} was rewired onto {@code McpDispatcher}.
     * </p>
     *
     * @return a new list of freshly constructed default tools, in {@code tools/list} order
     */
    static List<McpTool> defaultTools() {
        return List.of(new SearchTool(), new IndexStatsTool(), new SuggestTool(), new GetDocumentTool());
    }
}
