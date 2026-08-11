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

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.util.ComponentUtil;

/**
 * Base for the six {@link McpMethodHandler}s whose result is a {@code CacheableResult}
 * ({@code ttlMs} + {@code cacheScope} both required): {@code server/discover}, {@code
 * tools/list}, {@code resources/list}, {@code resources/read}, {@code resources/templates/list},
 * and {@code prompts/list}. {@code tools/call}, {@code prompts/get}, and
 * {@code completion/complete} are not {@code CacheableResult} in the schema and must not extend
 * this class.
 *
 * <p>
 * Centralises the two rules every one of the six must follow identically, so neither is
 * copy-pasted per handler:
 * </p>
 * <ul>
 * <li>{@code ttlMs} is read from a hot-reloadable system property. A missing or unparseable
 * value falls back to the handler's own default, and the result -- default included -- is
 * always clamped to {@code >= 0} before it is reported, per the schema's {@code @minimum 0}.</li>
 * <li>For the four list methods (not {@code resources/read}, which is not paginated), an
 * inbound non-null {@code cursor} is always rejected: this server never issues a
 * {@code nextCursor}, so any {@code cursor} a client sends is necessarily stale. An explicit
 * JSON {@code null} is treated as absent -- see {@link #rejectCursor}.</li>
 * </ul>
 */
public abstract class AbstractCacheableHandler implements McpMethodHandler {

    /**
     * The system property key selecting the MCP authentication mode.
     * <p>
     * Duplicated from {@code McpApiManager#getAuthMode()}'s literal key rather than shared: that
     * class (package {@code api.mcp}) already imports this package's handlers to build its
     * dispatcher, so importing back would create a cycle. {@link ToolsListHandler} and
     * {@link ResourcesListHandler} are the only two subclasses that consult it -- their result
     * varies by the caller's authorization once {@code get_index_stats} is gated, so a
     * {@code public} {@code cacheScope} could otherwise be shared across differently-authorized
     * callers.
     * </p>
     */
    protected static final String AUTH_MODE_CONFIG_KEY = "mcp.auth.mode";

    /** The value of {@value #AUTH_MODE_CONFIG_KEY} meaning "no authentication": every caller resolves to an anonymous principal. */
    protected static final String AUTH_MODE_NONE = "none";

    /** The hot-reloadable system property key holding this result's TTL. */
    private final String ttlConfigKey;

    /** The TTL to use when {@link #ttlConfigKey} is absent or unparseable. */
    private final long defaultTtlMs;

    /**
     * Creates a cacheable-result handler backed by the given TTL configuration.
     *
     * @param ttlConfigKey the hot-reloadable system property key holding this result's TTL
     * @param defaultTtlMs the TTL to use when the property is absent or unparseable
     */
    protected AbstractCacheableHandler(final String ttlConfigKey, final long defaultTtlMs) {
        this.ttlConfigKey = ttlConfigKey;
        this.defaultTtlMs = defaultTtlMs;
    }

    /**
     * Returns the Fess configuration component.
     *
     * @return the Fess configuration
     */
    protected FessConfig getFessConfig() {
        return ComponentUtil.getFessConfig();
    }

    /**
     * Reads this result's configured TTL, unclamped.
     *
     * <p>
     * A missing or unparseable value falls back to the handler's default. This method does not
     * apply the final {@code >= 0} floor itself -- {@link #resolveTtlMs()} does -- so a test
     * double can override this to return a negative value and still exercise the clamp.
     * </p>
     *
     * @return the configured TTL in milliseconds; may be negative
     */
    protected long getTtlMs() {
        final String raw = getFessConfig().getSystemProperty(ttlConfigKey);
        if (raw != null) {
            try {
                return Long.parseLong(raw.trim());
            } catch (final NumberFormatException e) {
                // fall through to the default
            }
        }
        return defaultTtlMs;
    }

    /**
     * Returns {@link #getTtlMs()} clamped to {@code >= 0}, per the schema's {@code @minimum 0}
     * on {@code CacheableResult.ttlMs}.
     *
     * @return the TTL to report; never negative
     */
    protected final long resolveTtlMs() {
        return Math.max(0L, getTtlMs());
    }

    /**
     * Returns the {@code cacheScope} to report for this result.
     *
     * @param context the call context
     * @return {@code "public"} or {@code "private"}
     */
    protected abstract String getCacheScope(McpCallContext context);

    /**
     * Returns the configured MCP authentication mode.
     * <p>
     * Delegates to {@link #getSystemProperty(String, String)} rather than calling
     * {@code getFessConfig().getSystemProperty(...)} directly, so a container-free test can stub
     * that one primitive and drive this method's real body -- including its literal
     * {@value #AUTH_MODE_CONFIG_KEY} key and {@link #AUTH_MODE_NONE} default argument -- without
     * needing a live DI container. {@code ToolsListHandler} and {@code ResourcesListHandler}
     * (the only two subclasses that consult this) both override {@code getAuthMode()} itself in
     * their tests, exactly like {@link #getTtlMs()}; without this seam, this method's own body
     * -- the actual key/default it passes -- would never be exercised by any test in this suite,
     * and a typo'd key would read a property that does not exist, silently and permanently
     * falling back to {@link #AUTH_MODE_NONE} in production.
     * </p>
     *
     * @return {@value #AUTH_MODE_CONFIG_KEY}'s value; {@value #AUTH_MODE_NONE} when unset
     */
    protected String getAuthMode() {
        return getSystemProperty(AUTH_MODE_CONFIG_KEY, AUTH_MODE_NONE);
    }

    /**
     * Reads a String-valued Fess system property.
     * <p>
     * Isolated so {@link #getAuthMode()} itself can be exercised container-free: this is the
     * only place in that call chain that touches {@code ComponentUtil}. Mirrors
     * {@code McpApiManager#getSystemProperty(String, String)} for the identical reason.
     * </p>
     *
     * @param key the system property key
     * @param defaultValue the value to return when the property is unset
     * @return the property's value, or {@code defaultValue} when unset
     */
    protected String getSystemProperty(final String key, final String defaultValue) {
        return getFessConfig().getSystemProperty(key, defaultValue);
    }

    /**
     * Rejects an inbound {@code cursor}.
     *
     * <p>
     * This server always returns every item in a single page and never issues a
     * {@code nextCursor}, so any {@code cursor} a client sends is necessarily stale. Silently
     * ignoring it and returning page 1 would mislead a client that believes it is paging past
     * page 1.
     * </p>
     *
     * <p>
     * <b>An explicit JSON {@code null} is absent, not a cursor</b> -- which is why this reads the
     * mapped value instead of asking {@code containsKey}. The schema declares
     * {@code cursor?: string}, so {@code "cursor": null} means "no cursor", exactly like omitting
     * the key; the two are the same request as far as the protocol is concerned. The distinction
     * matters because the OpenSearch XContent parser that builds {@code params} keeps
     * null-valued keys, so {@code containsKey("cursor")} answers true for both, while several
     * mainstream serializers emit a null for an unset optional field by default -- Jackson,
     * .NET's {@code System.Text.Json}, and a Go struct field without {@code omitempty} all do.
     * A client built on any of them would send {@code "cursor": null} on its very first
     * {@code tools/list} -- the call immediately after {@code server/discover} -- and, if the
     * key's mere presence were the test, be met with {@code -32602} on all four list methods and
     * never get off the ground. This is also what the pre-refactor implementation did: it
     * accepted {@code cursor} and ignored it entirely, so treating a null as a rejection would
     * be a regression, not a tightening.
     * </p>
     *
     * @param context the call context
     * @throws McpError with HTTP 200 and {@link ErrorCode#InvalidParams} when {@code params}
     *             carries a non-null {@code cursor}
     */
    protected void rejectCursor(final McpCallContext context) {
        if (context.getParams().get("cursor") != null) {
            throw new McpError(HttpServletResponse.SC_OK, ErrorCode.InvalidParams,
                    "this server returns a single page and never issues a nextCursor");
        }
    }

    /**
     * Stamps {@code ttlMs} (clamped) and {@code cacheScope} onto a result, per
     * {@code CacheableResult}.
     *
     * @param result the result map to mutate; must be mutable
     * @param context the call context
     */
    protected void putCacheHints(final Map<String, Object> result, final McpCallContext context) {
        result.put("ttlMs", resolveTtlMs());
        result.put("cacheScope", getCacheScope(context));
    }
}
