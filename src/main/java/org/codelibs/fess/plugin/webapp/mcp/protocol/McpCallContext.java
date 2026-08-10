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

import java.util.Collections;
import java.util.Map;

/**
 * Per-invocation context passed to
 * {@link org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler#handle(McpCallContext)}
 * and to {@link org.codelibs.fess.plugin.webapp.mcp.tool.McpTool#call(java.util.Map, McpCallContext)}.
 *
 * <p>
 * Carries the parsed request envelope, its {@code params._meta}, and the request's
 * {@code params} for direct access. Deliberately does not yet carry a resolved
 * {@code McpPrincipal}: permission enforcement is a later task's work, and adding that field
 * then is not a breaking change for any existing {@code McpMethodHandler} or {@code McpTool}
 * implementation, exactly as adding {@code request}/{@code meta}/{@code params} here was not a
 * breaking change for the {@code McpTool} implementations that predate them.
 * </p>
 */
public class McpCallContext {

    /** The parsed request envelope, or null when this context was created without one. */
    private final McpRequest request;

    /** The parsed {@code params._meta}, or null when this context was created without one. */
    private final McpRequestMeta meta;

    /** The request's {@code params}; never null. */
    private final Map<String, Object> params;

    /**
     * Creates an empty call context, carrying neither a request nor {@code _meta} nor any
     * params.
     * <p>
     * Used by {@code McpTool} unit tests, which exercise {@code call} directly and have no
     * full {@link McpRequest} to hand over.
     * </p>
     */
    public McpCallContext() {
        this(null, null, Collections.emptyMap());
    }

    /**
     * Creates a call context.
     *
     * @param request the parsed request envelope, or null when unavailable
     * @param meta the parsed {@code params._meta}, or null when unavailable
     * @param params the request's {@code params}; null is treated as empty
     */
    public McpCallContext(final McpRequest request, final McpRequestMeta meta, final Map<String, Object> params) {
        this.request = request;
        this.meta = meta;
        this.params = params != null ? params : Collections.emptyMap();
    }

    /**
     * Returns the parsed request envelope.
     *
     * @return the request, or null when this context was created without one
     */
    public McpRequest getRequest() {
        return request;
    }

    /**
     * Returns the parsed {@code params._meta}.
     *
     * @return the request metadata, or null when this context was created without one
     */
    public McpRequestMeta getMeta() {
        return meta;
    }

    /**
     * Returns the request's {@code params}.
     *
     * @return the params map; never null
     */
    public Map<String, Object> getParams() {
        return params;
    }
}
