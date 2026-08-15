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
package org.codelibs.fess.plugin.webapp.api.mcp;

import java.nio.charset.StandardCharsets;

import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.dbflute.utflute.mocklet.MockletServletContextImpl;

/**
 * Container-free helpers for exercising {@code McpApiManager#process}.
 *
 * <p>
 * {@code MockletHttpServletRequestImpl} throws {@code UnsupportedOperationException} from
 * both {@code getInputStream()} and {@code getReader()}, so tests using this support class
 * must supply the request body through the {@code readRequestBody} protected seam on
 * {@code McpApiManager} rather than through the mock request itself.
 * </p>
 */
public final class McpHttpTestSupport {

    private McpHttpTestSupport() {
        // no instantiation
    }

    /**
     * Creates a mock request. {@code MockletHttpServletRequestImpl} already initialises its
     * cookie list to an empty array, so no explicit cookie setup is required before handing
     * the request to {@link #newResponse(MockletHttpServletRequestImpl)}. Callers that need to
     * set request headers (e.g. {@code MCP-Protocol-Version}, {@code Mcp-Method}, {@code Mcp-Name})
     * can call {@code addHeader(String, String)} directly on the returned request.
     *
     * @param method the HTTP method, e.g. {@code "POST"}
     * @param servletPath the servlet path, e.g. {@code "/mcp"}
     * @return a mock request
     */
    public static MockletHttpServletRequestImpl newRequest(final String method, final String servletPath) {
        final MockletHttpServletRequestImpl request = new MockletHttpServletRequestImpl(new MockletServletContextImpl("/"), servletPath);
        request.setMethod(method);
        return request;
    }

    /**
     * Creates a mock response bound to the given request.
     *
     * @param request the request the response belongs to
     * @return a mock response
     */
    public static MockletHttpServletResponseImpl newResponse(final MockletHttpServletRequestImpl request) {
        return new MockletHttpServletResponseImpl(request);
    }

    /**
     * Reads the response body from <em>both</em> sinks the mock keeps.
     *
     * <p>
     * {@code MockletHttpServletResponseImpl} has two independent body channels:
     * {@code getOutputStream()} drains into a {@code ByteArrayOutputStream} exposed by
     * {@code getResponseBytes()} -- the one this plugin's writers use -- and {@code getWriter()}
     * into a separate {@code StringWriter} exposed by {@code getResponseString()}. Reading only the
     * first left every {@code assertEquals("", bodyOf(response))} in this suite blind to a body
     * written through the other: a leak through {@code getWriter()} passed the whole suite. Nothing
     * in production uses {@code getWriter()} today, which is exactly why an assertion that cannot
     * see it is worth one line to fix rather than worth trusting.
     * </p>
     *
     * @param response the mock response
     * @return the response body as UTF-8 text, the byte sink followed by the writer sink
     */
    public static String bodyOf(final MockletHttpServletResponseImpl response) {
        final byte[] bytes = response.getResponseBytes();
        return (bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8)) + response.getResponseString();
    }
}
