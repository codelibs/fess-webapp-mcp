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

import org.codelibs.fess.plugin.webapp.mcp.auth.McpPrincipal;

/**
 * Per-invocation context passed to
 * {@link org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler#handle(McpCallContext)}
 * and to {@link org.codelibs.fess.plugin.webapp.mcp.tool.McpTool#call(java.util.Map, McpCallContext)}.
 *
 * <p>
 * Carries the parsed request envelope, its {@code params._meta}, the request's {@code params}
 * for direct access, and the {@link McpPrincipal} resolved by {@code McpApiManager#authenticate}
 * for this call. The principal lets a handler or tool -- most immediately the
 * {@code get_index_stats} permission gate -- decide what the caller may see without threading
 * the servlet request itself through the dispatch layer.
 * </p>
 */
public class McpCallContext {

    /** The parsed request envelope, or null when this context was created without one. */
    private final McpRequest request;

    /**
     * The parsed {@code params._meta}, or null when this context was created without one.
     * <p>
     * Retained as part of the validated request envelope this context carries, but deliberately
     * not exposed: the {@code getMeta()} accessor that used to return it had no caller in either
     * {@code src/main/java} or {@code src/test/java}, so it was removed rather than left as an
     * untested public surface. The two consumers of {@code _meta} that do exist -- the
     * protocol-version cross-check in {@code HeaderValidator} and the supported-version check in
     * {@code McpApiManager} -- both work with the {@link McpRequestMeta} directly, before this
     * context is built. The constructor parameter stays because it is what makes this context a
     * faithful record of the envelope that was validated; add an accessor back when a handler
     * actually needs to consult it, together with the test that pins the use.
     * </p>
     */
    private final McpRequestMeta meta;

    /** The request's {@code params}; never null. */
    private final Map<String, Object> params;

    /** The resolved caller; never null. */
    private final McpPrincipal principal;

    /**
     * Creates an empty call context, carrying neither a request nor {@code _meta} nor any
     * params, with an anonymous principal.
     * <p>
     * Used by {@code McpTool} unit tests, which exercise {@code call} directly and have no
     * full {@link McpRequest} to hand over.
     * </p>
     */
    public McpCallContext() {
        this(null, null, Collections.emptyMap());
    }

    /**
     * Creates a call context with an anonymous principal.
     *
     * @param request the parsed request envelope, or null when unavailable
     * @param meta the parsed {@code params._meta}, or null when unavailable
     * @param params the request's {@code params}; null is treated as empty
     */
    public McpCallContext(final McpRequest request, final McpRequestMeta meta, final Map<String, Object> params) {
        this(request, meta, params, McpPrincipal.anonymous());
    }

    /**
     * Creates a call context.
     *
     * @param request the parsed request envelope, or null when unavailable
     * @param meta the parsed {@code params._meta}, or null when unavailable
     * @param params the request's {@code params}; null is treated as empty
     * @param principal the resolved caller; null is treated as {@link McpPrincipal#anonymous()}
     */
    public McpCallContext(final McpRequest request, final McpRequestMeta meta, final Map<String, Object> params,
            final McpPrincipal principal) {
        this.request = request;
        this.meta = meta;
        this.params = params != null ? params : Collections.emptyMap();
        this.principal = principal != null ? principal : McpPrincipal.anonymous();
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
     * Returns the request's {@code params}.
     *
     * @return the params map; never null
     */
    public Map<String, Object> getParams() {
        return params;
    }

    /**
     * Returns the resolved caller.
     *
     * @return the principal; never null, may be {@link McpPrincipal#anonymous()}
     */
    public McpPrincipal getPrincipal() {
        return principal;
    }
}
