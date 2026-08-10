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

/**
 * Per-invocation context passed to
 * {@link org.codelibs.fess.plugin.webapp.mcp.tool.McpTool#call(java.util.Map, McpCallContext)}.
 *
 * <p>
 * Intentionally minimal: this revision of the tool extraction has no tool implementation that
 * reads anything from it. It exists now, ahead of need, only so that {@code McpTool#call} has a
 * stable, typed second parameter instead of {@code Object}. A later task (permission enforcement)
 * is expected to add fields here, e.g. the caller's resolved roles or the parsed request
 * metadata; adding fields to this class is intentionally not a breaking change for any
 * {@code McpTool} implementation.
 * </p>
 */
public class McpCallContext {

    /**
     * Creates an empty call context.
     */
    public McpCallContext() {
        // intentionally empty; fields land in a later task
    }
}
