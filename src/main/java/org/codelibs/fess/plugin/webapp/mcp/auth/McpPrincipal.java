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
package org.codelibs.fess.plugin.webapp.mcp.auth;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The caller of an MCP request, as resolved by a {@link McpAuthenticator}.
 * <p>
 * Immutable and deliberately thin: it carries only what the rest of this plugin needs to make
 * an authorization decision -- a stable identifier, the OAuth scopes a token declared (empty
 * outside {@code oauth} mode), and the encoded Fess permission strings resolved for the caller.
 * {@link #getPermissions()} feeds directly into {@code McpApiManager#resolveRoles}, which seeds
 * Fess's {@code userRoles} request attribute with exactly this set (plus the configured
 * defaults), so these strings must already be in Fess's encoded form (e.g. {@code "Rguest"},
 * {@code "1guest"}) rather than the {@code {role}x} / {@code {user}x} authoring syntax.
 * </p>
 */
public final class McpPrincipal {

    /**
     * The shared instance {@link #anonymous()} returns. A single constant rather than a fresh
     * allocation per call: every {@code none}-mode request resolves to this same principal, and
     * that mode is the default, so this object is built once and reused for the lifetime of the
     * classloader.
     */
    private static final McpPrincipal ANONYMOUS = new McpPrincipal(null, Set.of(), Set.of());

    /** A stable identifier for the caller, or {@code null} when the caller is anonymous. */
    private final String subject;

    /** The OAuth scopes granted to the caller; empty outside {@code oauth} mode. */
    private final Set<String> scopes;

    /** The encoded Fess permission strings resolved for the caller. */
    private final Set<String> permissions;

    /**
     * Creates a principal.
     *
     * @param subject a stable identifier for the caller, or {@code null} for an anonymous caller
     * @param scopes the OAuth scopes granted to the caller; {@code null} is treated as empty
     * @param permissions the encoded Fess permission strings resolved for the caller;
     *            {@code null} is treated as empty
     */
    public McpPrincipal(final String subject, final Set<String> scopes, final Set<String> permissions) {
        this.subject = subject;
        this.scopes = copyOf(scopes);
        this.permissions = copyOf(permissions);
    }

    /**
     * Defensively copies a caller-supplied set into an unmodifiable one, treating {@code null}
     * as empty.
     *
     * @param values the set to copy, may be {@code null}
     * @return an unmodifiable copy, never {@code null}
     */
    private static Set<String> copyOf(final Set<String> values) {
        return values == null || values.isEmpty() ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    /**
     * Returns the shared anonymous principal: no subject, no scopes, no permissions.
     * <p>
     * Used by {@code none} mode (which never authenticates a caller at all) and as the
     * unauthenticated baseline other modes fall back to.
     * </p>
     *
     * @return the anonymous principal
     */
    public static McpPrincipal anonymous() {
        return ANONYMOUS;
    }

    /**
     * Returns a stable identifier for the caller.
     *
     * @return the subject, or {@code null} when the caller is anonymous
     */
    public String getSubject() {
        return subject;
    }

    /**
     * Returns the OAuth scopes granted to the caller.
     *
     * @return the scopes; empty outside {@code oauth} mode
     */
    public Set<String> getScopes() {
        return scopes;
    }

    /**
     * Returns the encoded Fess permission strings resolved for the caller.
     *
     * @return the permissions; empty for an anonymous or unprivileged caller
     */
    public Set<String> getPermissions() {
        return permissions;
    }
}
