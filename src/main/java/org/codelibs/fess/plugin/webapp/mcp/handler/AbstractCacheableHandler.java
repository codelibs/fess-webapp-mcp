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
 * inbound {@code cursor} is always rejected: this server never issues a {@code nextCursor}, so
 * any {@code cursor} a client sends is necessarily stale.</li>
 * </ul>
 */
public abstract class AbstractCacheableHandler implements McpMethodHandler {

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
     * Rejects an inbound {@code cursor}.
     *
     * <p>
     * This server always returns every item in a single page and never issues a
     * {@code nextCursor}, so any {@code cursor} a client sends is necessarily stale. Silently
     * ignoring it and returning page 1 would mislead a client that believes it is paging past
     * page 1.
     * </p>
     *
     * @param context the call context
     * @throws McpError with HTTP 200 and {@link ErrorCode#InvalidParams} when {@code params}
     *             carries a {@code cursor}
     */
    protected void rejectCursor(final McpCallContext context) {
        if (context.getParams().containsKey("cursor")) {
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
