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

import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;

/**
 * The {@code server/discover} handler.
 *
 * <p>
 * MCP 2026-07-28 deletes the {@code initialize} handshake entirely and makes
 * {@code server/discover} mandatory in its place. Unlike {@code initialize}, this method does
 * not negotiate a protocol version -- this server speaks exactly one revision -- and its result
 * carries {@code ttlMs} / {@code cacheScope} because, unlike {@code initialize}, it is a
 * {@code CacheableResult}.
 * </p>
 */
public class DiscoverHandler extends AbstractCacheableHandler {

    /** The hot-reloadable system property key holding this result's TTL. */
    protected static final String TTL_CONFIG_KEY = "mcp.cache.discover.ttl.ms";

    /** Default TTL when {@value #TTL_CONFIG_KEY} is unset or unparseable. */
    protected static final long DEFAULT_TTL_MS = 3_600_000L;

    /**
     * Instructions surfaced to MCP clients.
     * <p>
     * {@code server/discover} is unauthenticated and always {@code cacheScope: "public"} --
     * unlike {@code tools/list}, it is not gate-aware and must not become so, since that would
     * force it off the public cache scope for every caller (a larger design change than the
     * {@code get_index_stats} gate should carry). It must therefore never name a permission
     * gated tool: doing so would announce a primitive's existence in plain text even though
     * {@code tools/list} correctly hides it and {@code tools/call} correctly refuses it. This
     * previously named {@code get_index_stats} (unchanged from the retired {@code initialize}
     * response's {@code instructions} field) before that tool was gated; the reference was
     * removed rather than made conditional. See {@code McpToolTest#testIndexStatsIsPermissionGated}
     * for which tool that is today.
     * </p>
     */
    protected static final String INSTRUCTIONS =
            "Fess Enterprise Search Server. Use the 'search' tool to perform full-text search with Lucene-like query syntax "
                    + "(AND default, OR explicit, quotes for phrase, - for exclusion). " + "Use 'suggest' for query autocomplete.";

    /**
     * Creates a {@code server/discover} handler.
     */
    public DiscoverHandler() {
        super(TTL_CONFIG_KEY, DEFAULT_TTL_MS);
    }

    @Override
    public String getMethod() {
        return "server/discover";
    }

    @Override
    public Map<String, Object> handle(final McpCallContext context) {
        final Map<String, Object> capabilities = new LinkedHashMap<>();
        // Empty objects: advertising listChanged or subscribe would oblige us to implement
        // subscriptions/listen, and logging is deprecated in this revision.
        capabilities.put("tools", new LinkedHashMap<String, Object>());
        capabilities.put("resources", new LinkedHashMap<String, Object>());
        capabilities.put("prompts", new LinkedHashMap<String, Object>());
        capabilities.put("completions", new LinkedHashMap<String, Object>());

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("supportedVersions", List.copyOf(McpConstants.SUPPORTED_PROTOCOL_VERSIONS));
        result.put("capabilities", capabilities);
        result.put("instructions", INSTRUCTIONS);
        putCacheHints(result, context);
        return result;
    }

    @Override
    protected String getCacheScope(final McpCallContext context) {
        return "public";
    }

    /**
     * Returns this plugin's version for {@code _meta.serverInfo}.
     *
     * <p>
     * Not used by {@link #handle(McpCallContext)} itself -- {@code serverInfo} is
     * {@code McpResponseWriter}'s responsibility, stamped onto every result's {@code _meta}.
     * {@code McpApiManager} calls this method directly (via its own {@code DiscoverHandler}
     * instance) when constructing its {@code McpResponseWriter}, so the resolution logic
     * mandated by the 2026-07-28 migration -- read the manifest, fall back to {@code "unknown"}
     * -- lives in exactly one place. Public so that cross-package caller can reach it without
     * subclassing.
     * </p>
     *
     * @return the implementation version, or {@code "unknown"} when it is unavailable
     */
    public String resolveServerVersion() {
        final String version = getClass().getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }
}
