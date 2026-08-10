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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An RFC 9728 OAuth 2.0 Protected Resource Metadata document.
 * <p>
 * Immutable and deliberately narrow: it models exactly the four members
 * {@code McpMetadataApiManager} serves, in the shape a discovering client expects. Building one
 * with an empty {@code authorizationServer} is a caller error this class does not itself guard
 * against -- RFC 9728 requires {@code authorization_servers} to be non-empty, but the decision
 * of whether OAuth is usable at all belongs to the caller (see
 * {@code OAuthResourceServerAuthenticator#isUsable()}), which must check that <em>before</em>
 * ever constructing this class, rather than this constructor failing closed with an exception a
 * caller might not expect.
 * </p>
 */
public final class ProtectedResourceMetadata {

    /**
     * The scope value the spec says a protected-resource metadata document SHOULD NOT
     * advertise: it signals refresh-token issuance, which this resource server -- a pure token
     * verifier, not an authorization endpoint -- never performs.
     */
    private static final String OFFLINE_ACCESS_SCOPE = "offline_access";

    /** The {@code bearer_methods_supported} value: this server accepts the token only via the {@code Authorization} header. */
    private static final List<String> BEARER_METHODS_SUPPORTED = List.of("header");

    /** The protected resource's own canonical identifier. */
    private final String resource;

    /** The single authorization server that issues tokens for {@link #resource}. */
    private final String authorizationServer;

    /** The OAuth scopes a client may request for this resource. */
    private final Set<String> scopesSupported;

    /**
     * Creates a protected-resource metadata document.
     *
     * @param resource the canonical resource URI, as resolved by {@link CanonicalResourceUri#resolve}
     * @param authorizationServer the issuer URL of the single authorization server trusted for
     *            {@code resource}
     * @param scopesSupported the OAuth scopes a client may request for this resource;
     *            {@code null} is treated as empty. {@code offline_access}, if present, is
     *            dropped from {@link #toMap()}'s output regardless
     */
    public ProtectedResourceMetadata(final String resource, final String authorizationServer, final Set<String> scopesSupported) {
        this.resource = resource;
        this.authorizationServer = authorizationServer;
        this.scopesSupported = scopesSupported == null ? Set.of() : scopesSupported;
    }

    /**
     * Renders this document as the JSON-serialisable map {@code McpMetadataApiManager} writes to
     * the response body.
     *
     * @return an insertion-ordered map with {@code resource}, {@code authorization_servers},
     *         {@code scopes_supported} (never containing {@code offline_access}), and
     *         {@code bearer_methods_supported}
     */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("resource", resource);
        map.put("authorization_servers", List.of(authorizationServer));
        map.put("scopes_supported",
                scopesSupported.stream().filter(scope -> !OFFLINE_ACCESS_SCOPE.equals(scope)).collect(Collectors.toList()));
        map.put("bearer_methods_supported", BEARER_METHODS_SUPPORTED);
        return map;
    }
}
