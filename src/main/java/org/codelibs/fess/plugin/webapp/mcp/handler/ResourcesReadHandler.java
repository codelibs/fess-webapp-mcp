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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.helper.SearchHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.auth.PermissionGate;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.tool.IndexStatsTool;
import org.codelibs.fess.plugin.webapp.mcp.tool.McpTool;
import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.optional.OptionalThing;
import org.opensearch.common.xcontent.json.JsonXContent;

/**
 * The {@code resources/read} handler.
 *
 * <p>
 * Validates {@code uri} against the two resources this server actually publishes -- the static
 * {@code fess://index/stats} and the {@code fess://document/{doc_id}} template -- instead of
 * accepting any string. This is the MUST from the spec's Security Considerations: a server must
 * validate every resource URI, and, when it publishes a template, normalise/validate the
 * extracted variable. Rejecting anything that does not strictly match either shape closes off
 * traversal-style input by construction, without needing a separate normalisation step.
 * </p>
 */
public class ResourcesReadHandler extends AbstractCacheableHandler {

    /** The hot-reloadable system property key holding this result's TTL. */
    protected static final String TTL_CONFIG_KEY = "mcp.cache.read.ttl.ms";

    /** Default TTL when {@value #TTL_CONFIG_KEY} is unset or unparseable: reads are not cached by default. */
    protected static final long DEFAULT_TTL_MS = 0L;

    /** The one static resource URI this server publishes. */
    protected static final String STATS_URI = "fess://index/stats";

    /** The exact shape a {@code fess://document/{doc_id}} URI must match; capture group 1 is {@code doc_id}. */
    private static final Pattern DOCUMENT_URI = Pattern.compile("^fess://document/([A-Za-z0-9_-]{1,256})$");

    /** The tool whose {@link McpTool#getRequiredPermissions()} gates {@value #STATS_URI}. */
    private final McpTool indexStatsTool;

    /**
     * Creates a {@code resources/read} handler backed by a fresh {@link IndexStatsTool}.
     */
    public ResourcesReadHandler() {
        this(new IndexStatsTool());
    }

    /**
     * Creates a {@code resources/read} handler.
     *
     * @param indexStatsTool the tool whose {@code getRequiredPermissions()} gates
     *            {@value #STATS_URI}; the same primitive {@code ToolsListHandler} and
     *            {@code ToolsCallHandler} gate as {@code get_index_stats}, so a read and a list
     *            agree
     */
    public ResourcesReadHandler(final McpTool indexStatsTool) {
        super(TTL_CONFIG_KEY, DEFAULT_TTL_MS);
        this.indexStatsTool = indexStatsTool;
    }

    @Override
    public String getMethod() {
        return "resources/read";
    }

    @Override
    public Map<String, Object> handle(final McpCallContext context) {
        final Object uriObj = context.getParams().get("uri");
        final String uri = uriObj instanceof String ? (String) uriObj : null;
        if (uri == null || uri.isBlank()) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Missing required parameter: uri");
        }

        final Map<String, Object> result;
        if (STATS_URI.equals(uri)) {
            // Same throw site (same message, same ErrorCode, same HTTP status) as the "no URI
            // matches at all" branch below: a caller who lacks the permission must not be able
            // to tell this resource apart from one that was never published at all.
            if (!PermissionGate.isAllowed(indexStatsTool.getRequiredPermissions(), context.getPrincipal())) {
                throw notFound(uri);
            }
            result = buildIndexStatsResource();
        } else {
            final Matcher matcher = DOCUMENT_URI.matcher(uri);
            if (matcher.matches()) {
                result = buildDocumentResource(matcher.group(1));
            } else {
                // Never return an empty contents array for a resource that does not exist.
                throw notFound(uri);
            }
        }
        putCacheHints(result, context);
        return result;
    }

    /**
     * Builds the "resource not found" error for a {@code uri} this server does not publish, or
     * that the caller lacks the permission to read.
     * <p>
     * {@code -32602} at HTTP 200: this is an application-level not-found, unlike a malformed
     * {@code _meta}. Used identically for an unknown URI and for a gated one the caller may not
     * read, so the two are indistinguishable to the caller.
     * </p>
     *
     * @param uri the requested URI, echoed in the message
     * @return the error for the caller to throw
     */
    protected McpError notFound(final String uri) {
        return new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Resource not found: " + uri);
    }

    @Override
    protected String getCacheScope(final McpCallContext context) {
        return "private";
    }

    /**
     * Builds a document resource by fetching the document with the given ID.
     *
     * @param docId the document ID, already validated against {@link #DOCUMENT_URI}
     * @return a mutable map with {@code contents}
     * @throws McpError with HTTP 200 and {@link ErrorCode#InvalidParams} when no document has
     *             this ID, or with HTTP 200 and {@link ErrorCode#InternalError} when the document
     *             cannot be serialised
     */
    protected Map<String, Object> buildDocumentResource(final String docId) {
        final FessConfig fessConfig = getFessConfig();
        final String[] fields = { fessConfig.getIndexFieldTitle(), fessConfig.getIndexFieldContent(), fessConfig.getIndexFieldUrl(),
                fessConfig.getIndexFieldDocId() };

        return getSearchHelper().getDocumentByDocId(docId, fields, OptionalThing.empty()).map(doc -> {
            try {
                final String jsonResult = JsonXContent.contentBuilder().map(doc).toString();
                final Map<String, Object> content = new LinkedHashMap<>();
                content.put("uri", "fess://document/" + docId);
                content.put("mimeType", "application/json");
                content.put("text", jsonResult);

                final Map<String, Object> result = new LinkedHashMap<>();
                result.put("contents", List.of(content));
                return result;
            } catch (final IOException e) {
                throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InternalError, "Failed to serialize document: " + e.getMessage());
            }
        }).orElseThrow(() -> new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams, "Document not found: " + docId));
    }

    /**
     * Builds the index stats resource content.
     * <p>
     * Deliberately constructs its own {@link IndexStatsTool} rather than reusing
     * {@link #indexStatsTool} (the instance {@code handle()} consults only for the permission
     * gate): the two cannot diverge in production -- both are stock {@code IndexStatsTool}
     * instances backed by the same {@code mcp.tools.index_stats.permissions} system property --
     * and this method is only ever reached after {@link #indexStatsTool}'s gate has already
     * passed, so the one reachable divergence (a caller-supplied {@link #indexStatsTool} with a
     * looser gate than a genuinely different tool used here) is fail-closed, not fail-open: it
     * would make this resource harder to reach, never easier.
     * </p>
     *
     * @return a mutable map with {@code contents}
     * @throws McpError with HTTP 200 and {@link ErrorCode#InternalError} when the stats cannot
     *             be serialised
     */
    protected Map<String, Object> buildIndexStatsResource() {
        try {
            final Map<String, Object> stats = new IndexStatsTool().collectIndexStats();
            final String jsonResult = JsonXContent.contentBuilder().map(stats).toString();

            final Map<String, Object> content = new LinkedHashMap<>();
            content.put("uri", STATS_URI);
            content.put("mimeType", "application/json");
            content.put("text", jsonResult);

            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("contents", List.of(content));
            return result;
        } catch (final IOException e) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InternalError, "Failed to serialize index stats: " + e.getMessage());
        }
    }

    /**
     * Returns the search helper component used to look up a document by ID.
     *
     * @return the search helper
     */
    protected SearchHelper getSearchHelper() {
        return ComponentUtil.getSearchHelper();
    }
}
