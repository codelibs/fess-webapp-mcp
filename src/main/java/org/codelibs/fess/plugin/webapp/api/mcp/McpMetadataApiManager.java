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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.api.WebApiManager;
import org.codelibs.fess.plugin.webapp.mcp.auth.CanonicalResourceUri;
import org.codelibs.fess.plugin.webapp.mcp.auth.OAuthResourceServerAuthenticator;
import org.codelibs.fess.plugin.webapp.mcp.auth.ProtectedResourceMetadata;
import org.codelibs.fess.plugin.webapp.mcp.json.Json;
import org.codelibs.fess.util.ComponentUtil;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the RFC 9728 OAuth 2.0 Protected Resource Metadata document at
 * {@code /.well-known/oauth-protected-resource} and its {@code /mcp}-scoped form.
 * <p>
 * Implements {@link WebApiManager} directly rather than extending {@code BaseApiManager} (the
 * way {@link McpApiManager} does): {@code BaseApiManager} is abstract specifically to force a
 * {@code writeHeaders} override and carries a {@code FormatType} enum plus a
 * {@code write(String, String, String)} helper built around {@code LaResponseUtil}'s
 * thread-local response and Fess's search-API content negotiation (label/favorite/scroll/...) --
 * none of which this two-path, always-JSON, container-free-testable endpoint has any use for.
 * Implementing {@link WebApiManager} directly keeps this class to exactly the two methods that
 * interface requires.
 * </p>
 * <p>
 * Registered as its own Lasta DI component in {@code fess_api++.xml}, separate from
 * {@link McpApiManager}'s. Being declared there is not enough by itself: like
 * {@link McpApiManager#register()}, {@link #register()} must independently call
 * {@code ComponentUtil.getWebApiManagerFactory().add(this)} from a {@code @PostConstruct}
 * callback, or this manager is constructed by the container but never consulted for any request.
 * </p>
 */
public class McpMetadataApiManager implements WebApiManager {

    private static final Logger logger = LogManager.getLogger(McpMetadataApiManager.class);

    /** The bare, resource-path-independent well-known path some clients probe before trying a resource-scoped one. */
    private static final String ROOT_PATH = "/.well-known/oauth-protected-resource";

    /** The {@code /mcp}-scoped well-known path RFC 9728 &#xa7;3.1 would construct for this server's one resource. */
    private static final String MCP_SCOPED_PATH = "/.well-known/oauth-protected-resource/mcp";

    /** Content type applied to the response body. */
    private static final String CONTENT_TYPE = "application/json; charset=UTF-8";

    /**
     * Creates a metadata API manager. Stateless: every {@code ComponentUtil} read happens inside
     * {@link #process}, not here, so construction costs a container-free test nothing.
     */
    public McpMetadataApiManager() {
        // no state
    }

    /**
     * Registers this API manager with the {@code WebApiManagerFactory}.
     * <p>
     * A DI component declaration in {@code fess_api++.xml} alone does not put an instance in the
     * factory {@code McpApiManager#matches}/{@code #process} dispatch consults; this
     * {@code @PostConstruct} callback is what actually does that, exactly as
     * {@link McpApiManager#register()} does for the sibling {@code /mcp} endpoint.
     * </p>
     */
    @PostConstruct
    public void register() {
        if (logger.isInfoEnabled()) {
            logger.info("Load {}", this.getClass().getSimpleName());
        }
        ComponentUtil.getWebApiManagerFactory().add(this);
    }

    @Override
    public boolean matches(final HttpServletRequest request) {
        // Exact match only, on both paths: registration order across plugins' WebApiManagers is
        // not deterministic, so a prefix match here could shadow an unrelated manager registered
        // after this one for some other path beginning with this well-known prefix.
        final String path = request.getServletPath();
        return ROOT_PATH.equals(path) || MCP_SCOPED_PATH.equals(path);
    }

    @Override
    public void process(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        if (!McpApiManager.AUTH_MODE_OAUTH.equals(getAuthMode())) {
            // Not sendError(): see SendErrorProhibitedTest -- this endpoint is not recognised by
            // WebApiUtil#isApiRequestUri either, so a container sendError() would become a 302.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        final String issuer = getIssuer();
        if (!CanonicalResourceUri.isUsableConfiguration(issuer, getConfiguredAudience(), getJwksUri())) {
            // Defence in depth, mirroring OAuthResourceServerAuthenticator#isUsable(): this
            // manager reads mcp.auth.mode/mcp.oauth.* independently, through its own
            // ComponentUtil seams rather than by consulting an authenticator instance, so it must
            // independently apply the exact same usability predicate rather than trusting that
            // the two config reads can never disagree. A blank issuer would serve a PRM document
            // with an empty authorization_servers array (RFC 9728 requires it non-empty); a blank
            // or incompatible audience would derive (or otherwise misstate) this document's own
            // "resource" field from the request's caller-controlled Host header instead of the
            // operator-pinned value (C1); a blank jwks.uri means the sibling /mcp endpoint has
            // already fallen back to none-mode (anonymous) behaviour for this exact
            // configuration, so serving 200 here would advertise OAuth protection this deployment
            // is not actually enforcing (I1). See CanonicalResourceUri#isUsableConfiguration.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        final String canonicalUri = resolveCanonicalUri(request);
        final Map<String, Object> body = new ProtectedResourceMetadata(canonicalUri, issuer, getScopesSupported()).toMap();
        write(response, Json.write(body));
    }

    /**
     * Writes the response body. Never calls {@code sendError} or {@code sendRedirect}.
     *
     * @param response the servlet response
     * @param json the response body
     */
    protected void write(final HttpServletResponse response, final String json) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CONTENT_TYPE);
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        } catch (final IOException e) {
            logger.warn("[MCP] Failed to write protected-resource metadata: error={}", e.getMessage(), e);
        }
    }

    /**
     * Resolves the canonical resource URI for {@code request}, per {@link CanonicalResourceUri}
     * -- the same value {@link org.codelibs.fess.plugin.webapp.mcp.auth.OAuthResourceServerAuthenticator}
     * checks the token's {@code aud} against and attaches to a challenge's
     * {@code resource_metadata}.
     *
     * @param request the servlet request
     * @return the canonical resource URI
     */
    protected String resolveCanonicalUri(final HttpServletRequest request) {
        return CanonicalResourceUri.resolve(request, getConfiguredAudience(), getTrustedProxies());
    }

    /**
     * Returns the configured authentication mode.
     *
     * @return {@code mcp.auth.mode}'s value; {@link McpApiManager#AUTH_MODE_NONE} when unset
     */
    protected String getAuthMode() {
        return getSystemProperty("mcp.auth.mode", McpApiManager.AUTH_MODE_NONE);
    }

    /**
     * Returns the configured authorization server issuer.
     *
     * @return {@code mcp.oauth.issuer}'s value; blank when unset
     */
    protected String getIssuer() {
        return getSystemProperty("mcp.oauth.issuer", StringUtil.EMPTY);
    }

    /**
     * Returns the configured audience.
     * <p>
     * Required for {@link CanonicalResourceUri#isUsableConfiguration} to accept this
     * configuration (C1): a blank value now makes {@link #process} refuse with HTTP 404 rather
     * than deriving the served {@code resource} field from the request the way {@link
     * CanonicalResourceUri#resolve}'s own {@code configuredAudience} parameter still documents as
     * its general contract -- that derivation is retained in {@link CanonicalResourceUri} itself
     * but is no longer reachable from this class with a blank value here.
     * </p>
     *
     * @return {@code mcp.oauth.audience}'s value; blank means oauth mode is not configured
     */
    protected String getConfiguredAudience() {
        return getSystemProperty("mcp.oauth.audience", StringUtil.EMPTY);
    }

    /**
     * Returns the configured JWKS endpoint.
     * <p>
     * Required for {@link CanonicalResourceUri#isUsableConfiguration} to accept this
     * configuration (I1): without it, {@code OAuthResourceServerAuthenticator#isUsable()} has
     * already made the sibling {@code /mcp} endpoint fall back to {@code none}-mode (anonymous)
     * behaviour for this exact configuration, so this manager must refuse too, rather than
     * serving a 200 protected-resource document that advertises OAuth protection the {@code /mcp}
     * endpoint is not actually enforcing.
     * </p>
     *
     * @return {@code mcp.oauth.jwks.uri}'s value; blank when unset
     */
    protected String getJwksUri() {
        return getSystemProperty("mcp.oauth.jwks.uri", StringUtil.EMPTY);
    }

    /**
     * Returns the configured trusted-proxy set, the same property Fess's own
     * {@code TargetOriginResolver} uses for the same trust decision.
     *
     * @return the trusted proxy IP addresses; empty when unset
     */
    protected Set<String> getTrustedProxies() {
        return ComponentUtil.getFessConfig().getRateLimitTrustedProxiesAsSet();
    }

    /**
     * Returns the scopes advertised in the served document's {@code scopes_supported}.
     * <p>
     * Reuses {@code mcp.oauth.required.scopes} -- the same property
     * {@code OAuthResourceServerAuthenticator#getRequiredScopes()} enforces -- so the advertised
     * scopes and the enforced scopes can never drift apart from independently-maintained config.
     * </p>
     *
     * @return the parsed {@code mcp.oauth.required.scopes}; empty when unset
     */
    protected Set<String> getScopesSupported() {
        // Delegates to OAuthResourceServerAuthenticator's parser rather than re-implementing the
        // same comma-split/trim/filter logic here: mcp.oauth.required.scopes is read by both
        // classes (that one to enforce it, this one to advertise it), and a single parser is the
        // only way to guarantee the two can never silently diverge on what counts as a valid
        // scope token.
        return OAuthResourceServerAuthenticator.parseScopeList(getSystemProperty("mcp.oauth.required.scopes", StringUtil.EMPTY));
    }

    /**
     * Reads a String-valued Fess system property.
     *
     * @param key the system property key
     * @param defaultValue the value to return when the property is unset
     * @return the property's value, or {@code defaultValue} when unset
     */
    protected String getSystemProperty(final String key, final String defaultValue) {
        return ComponentUtil.getFessConfig().getSystemProperty(key, defaultValue);
    }
}
