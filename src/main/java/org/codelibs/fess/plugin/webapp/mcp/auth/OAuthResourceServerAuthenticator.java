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

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.util.ComponentUtil;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@code mcp.auth.mode=oauth} strategy: validates an RFC 6750 bearer JWT against the
 * configured authorization server's JWKS, per RFC 9728 (OAuth 2.0 Protected Resource Metadata)
 * and the MCP Authorization specification.
 * <p>
 * Checked, in order, once a bearer token is present: signature (via {@link #getProcessor()},
 * which also enforces {@code exp}/{@code nbf} -- see below), {@code iss} equals
 * {@link #getIssuer()}, {@code aud} contains {@link CanonicalResourceUri#resolve the canonical
 * resource URI} (RFC 8707), and {@link #getRequiredScopes() mcp.oauth.required.scopes} is a
 * subset of the token's {@code scope} claim. A request with no {@code Authorization} header at
 * all never reaches any of this -- see {@link #authenticate}.
 * </p>
 * <p>
 * <b>No scope-hierarchy resolution.</b> The MCP Authorization specification asks a resource
 * server to account for scope hierarchies a authorization server might define (e.g. a hypothetical
 * {@code fess:admin} implying {@code fess:search}). This class does not: {@link #requireScopes}
 * is a plain subset check against the token's literal {@code scope} claim. Operators must write
 * {@code mcp.oauth.required.scopes} using the authorization server's own leaf scopes -- the
 * scopes an issued token actually carries -- not an abstract parent scope the AS might expand
 * server-side.
 * </p>
 * <p>
 * Permission mapping is the union of two independent, both-optional sources: the
 * {@link #getPermissionClaim() mcp.oauth.permission.claim} JWT claim (already-encoded Fess
 * permission strings, e.g. {@code "Rguest"}) and {@link #getScopePermissionMap()
 * mcp.oauth.scope.permission.map} (each of the token's scopes mapped to zero or more encoded
 * permissions). An empty union is not compensated here: {@code McpApiManager#resolveRoles}
 * -- reached because {@link #ownsRoleResolution()} returns {@code true} -- already falls back to
 * the configured guest role list for an empty permission set and unconditionally adds
 * {@code role.search.default.permissions}, exactly as it does for {@link FessTokenAuthenticator}.
 * </p>
 */
public class OAuthResourceServerAuthenticator implements McpAuthenticator {

    private static final Logger logger = LogManager.getLogger(OAuthResourceServerAuthenticator.class);

    /** The HTTP response header carrying the RFC 6750 challenge. */
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    /** The {@code Authorization} request header name. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** The realm reported on every challenge this class emits. */
    private static final String REALM = "fess-mcp";

    /** The default JWKS cache lifetime, in seconds, when {@code mcp.oauth.jwks.cache.seconds} is unset. */
    private static final int DEFAULT_JWKS_CACHE_SECONDS = 300;

    /** The JWKS cache refresh timeout passed to {@link JWKSourceBuilder#cache(long, long)}, in milliseconds. */
    private static final long JWKS_CACHE_REFRESH_TIMEOUT_MILLIS = 30_000L;

    /**
     * The lazily-built, cached JWT processor. Built at most once per instance, on the first
     * request that actually presents a bearer token -- never at construction, so a
     * container-free test that never calls {@link #authenticate} (or that overrides
     * {@link #getProcessor()} directly) never touches {@link #newProcessor(String)}, network
     * I/O, or {@code ComponentUtil}.
     */
    private volatile ConfigurableJWTProcessor<SecurityContext> cachedProcessor;

    /**
     * Creates an {@code oauth}-mode authenticator. Stateless at construction time: every
     * {@code ComponentUtil} read and the JWKS processor are built lazily, on first use.
     */
    public OAuthResourceServerAuthenticator() {
        // no state
    }

    /**
     * Returns whether this authenticator is usable: {@code mcp.oauth.issuer} is configured, and
     * a configured {@code mcp.oauth.audience} (if any) is compatible with the fixed
     * {@code resource_metadata} shape this server actually serves.
     * <p>
     * RFC 9728 requires a protected-resource metadata document's {@code authorization_servers}
     * to be non-empty; serving one with none would be worse than not enabling authorization at
     * all. Separately, {@link CanonicalResourceUri#metadataUrl} only implements the well-known
     * path insertion for a resource path of exactly {@code /mcp}: an audience configured with a
     * different path (e.g. {@code https://host/api/mcp}) would make every challenge advertise a
     * {@code resource_metadata} URL {@code McpMetadataApiManager}'s exact-match {@code matches()}
     * does not serve. Both are treated as "not usable" rather than serving a broken deployment.
     * {@code McpApiManager#getAuthenticator} and {@code McpMetadataApiManager} both consult this
     * before selecting this class, falling back to {@code none}-mode behaviour (and, for the
     * metadata endpoint, HTTP 404) when it returns {@code false}.
     * </p>
     *
     * @return {@code true} when {@link #getIssuer()} is non-blank and
     *         {@link CanonicalResourceUri#isCompatibleAudience} accepts {@link #getConfiguredAudience()}
     */
    public boolean isUsable() {
        return StringUtil.isNotBlank(getIssuer()) && CanonicalResourceUri.isCompatibleAudience(getConfiguredAudience());
    }

    @Override
    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
        final String canonicalUri = resolveCanonicalUri(request);
        final String metadataUrl = CanonicalResourceUri.metadataUrl(canonicalUri);
        final Set<String> requiredScopes = getRequiredScopes();
        final String token = FessTokenAuthenticator.extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (token == null) {
            throw missingCredential(response, requiredScopes, metadataUrl);
        }
        try {
            final JWTClaimsSet claims = getProcessor().process(token, null);
            requireIssuer(claims);
            requireAudience(claims, canonicalUri);
            final Set<String> tokenScopes = extractScopes(claims);
            requireScopes(tokenScopes, requiredScopes, response, metadataUrl);
            return new McpPrincipal(claims.getSubject(), tokenScopes, resolvePermissions(claims, tokenScopes));
        } catch (final McpError e) {
            // Already fully shaped (challenge header set, correct HTTP status) by requireScopes.
            throw e;
        } catch (final ParseException | BadJOSEException | JOSEException | RuntimeException e) {
            throw invalidToken(response, requiredScopes, metadataUrl, e);
        }
    }

    @Override
    public boolean ownsRoleResolution() {
        return true;
    }

    /**
     * Resolves the canonical resource URI for {@code request}, per {@link CanonicalResourceUri}.
     *
     * @param request the servlet request
     * @return the canonical resource URI
     */
    protected String resolveCanonicalUri(final HttpServletRequest request) {
        return CanonicalResourceUri.resolve(request, getConfiguredAudience(), getTrustedProxies());
    }

    /**
     * Rejects a claims set whose {@code iss} does not equal {@link #getIssuer()}.
     *
     * @param claims the verified claims set
     * @throws IllegalArgumentException when the issuer does not match; caught and reshaped by
     *             {@link #authenticate}'s generic catch clause into a 401 {@code invalid_token}
     */
    private void requireIssuer(final JWTClaimsSet claims) {
        if (!getIssuer().equals(claims.getIssuer())) {
            throw new IllegalArgumentException("unexpected issuer");
        }
    }

    /**
     * Rejects a claims set whose {@code aud} does not contain {@code canonicalUri} (RFC 8707).
     *
     * @param claims the verified claims set
     * @param canonicalUri the canonical resource URI this server identifies as
     * @throws IllegalArgumentException when the audience does not include {@code canonicalUri};
     *             caught and reshaped by {@link #authenticate}'s generic catch clause into a 401
     *             {@code invalid_token}
     */
    private void requireAudience(final JWTClaimsSet claims, final String canonicalUri) {
        final List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(canonicalUri)) {
            throw new IllegalArgumentException("audience does not include the canonical resource URI");
        }
    }

    /**
     * Rejects a token whose {@code scope} claim does not cover every entry of {@code required}.
     *
     * @param tokenScopes the scopes carried by the token
     * @param required the configured {@code mcp.oauth.required.scopes}; an empty set means no
     *            scope is required
     * @param response the servlet response to attach the challenge header to
     * @param metadataUrl the {@code resource_metadata} URL to attach to the challenge
     * @throws McpError with HTTP 403 and an {@code insufficient_scope} challenge when
     *             {@code tokenScopes} does not contain every entry of {@code required}
     */
    private void requireScopes(final Set<String> tokenScopes, final Set<String> required, final HttpServletResponse response,
            final String metadataUrl) {
        if (required.isEmpty() || tokenScopes.containsAll(required)) {
            return;
        }
        setChallenge(response, "insufficient_scope", "The token is missing a required scope.", required, metadataUrl);
        throw new McpError(HttpServletResponse.SC_FORBIDDEN, ErrorCode.InvalidRequest, "The token is missing a required scope.");
    }

    /**
     * Maps a verified claims set to the encoded Fess permissions it grants, per this class's own
     * Javadoc ("Permission mapping").
     *
     * @param claims the verified claims set
     * @param tokenScopes the token's scopes, already extracted by {@link #extractScopes}
     * @return the union of the permission claim's values and the scope-to-permission map's
     *         values; empty when neither source contributes anything
     */
    protected Set<String> resolvePermissions(final JWTClaimsSet claims, final Set<String> tokenScopes) {
        final Set<String> permissions = new LinkedHashSet<>();
        final String claimName = getPermissionClaim();
        if (StringUtil.isNotBlank(claimName)) {
            permissions.addAll(readClaimAsStrings(claims, claimName));
        }
        final Map<String, Set<String>> scopeMap = getScopePermissionMap();
        for (final String scope : tokenScopes) {
            final Set<String> mapped = scopeMap.get(scope);
            if (mapped != null) {
                permissions.addAll(mapped);
            }
        }
        return permissions;
    }

    /**
     * Reads a claim as a set of strings, accepting either a JSON array or a space-delimited
     * string (the same convention {@link #extractScopes} uses for the {@code scope} claim).
     *
     * @param claims the claims set
     * @param name the claim name
     * @return the claim's values as strings; empty when the claim is absent, blank, or of an
     *         unsupported type
     */
    private Set<String> readClaimAsStrings(final JWTClaimsSet claims, final String name) {
        final Object value = claims.getClaim(name);
        if (value instanceof final List<?> list) {
            final Set<String> result = new LinkedHashSet<>();
            for (final Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        if (value instanceof final String text && StringUtil.isNotBlank(text)) {
            return splitOnWhitespace(text);
        }
        return Set.of();
    }

    /**
     * Extracts the token's OAuth scopes from its {@code scope} claim (RFC 9068 &#xa7;2.2.1: a
     * single, space-delimited string).
     *
     * @param claims the verified claims set
     * @return the scopes; empty when the {@code scope} claim is absent or blank
     * @throws ParseException if the {@code scope} claim is present but not a string
     */
    protected Set<String> extractScopes(final JWTClaimsSet claims) throws ParseException {
        final String scope = claims.getStringClaim("scope");
        return StringUtil.isBlank(scope) ? Set.of() : splitOnWhitespace(scope);
    }

    /**
     * Splits a space-delimited claim value into a set, preserving first-seen order.
     *
     * @param text the claim value, already known non-blank
     * @return the split, trimmed values
     */
    private static Set<String> splitOnWhitespace(final String text) {
        return Arrays.stream(text.trim().split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Builds the 401 to throw when the request carried no bearer credential at all.
     * <p>
     * Per RFC 6750 &#xa7;3, no {@code error} (or {@code error_description}) is set: that value
     * specifically claims a credential was supplied and rejected, which is not the case here.
     * {@code scope} and {@code resource_metadata} are still attached -- unlike the error
     * parameters, both are plain discovery information a client with no token yet still needs,
     * and withholding them would defeat the purpose of {@code resource_metadata} in the first
     * place.
     * </p>
     *
     * @param response the servlet response to attach the challenge header to
     * @param requiredScopes the configured {@code mcp.oauth.required.scopes}
     * @param metadataUrl the {@code resource_metadata} URL to attach to the challenge
     * @return the error to throw; never returns normally
     */
    private McpError missingCredential(final HttpServletResponse response, final Set<String> requiredScopes, final String metadataUrl) {
        setChallenge(response, null, null, requiredScopes, metadataUrl);
        return new McpError(HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.InvalidRequest, "A Bearer access token is required.");
    }

    /**
     * Builds the 401 to throw when the supplied token failed signature, issuer, audience, or
     * time-validity verification.
     * <p>
     * The failure detail is logged, not echoed to the caller, matching
     * {@link FessTokenAuthenticator#invalidToken}'s rationale: distinguishing "expired" from
     * "wrong audience" from "bad signature" in the response would help an attacker probe this
     * server's configuration for no benefit to a legitimate client, which cannot act on the
     * distinction anyway.
     * </p>
     *
     * @param response the servlet response to attach the challenge header to
     * @param requiredScopes the configured {@code mcp.oauth.required.scopes}
     * @param metadataUrl the {@code resource_metadata} URL to attach to the challenge
     * @param cause the verification failure; logged at debug level only
     * @return the error to throw; never returns normally
     */
    private McpError invalidToken(final HttpServletResponse response, final Set<String> requiredScopes, final String metadataUrl,
            final Exception cause) {
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] oauth authentication rejected a token: {}", cause.getMessage());
        }
        setChallenge(response, "invalid_token", "The access token is invalid, expired, or not valid for this resource.", requiredScopes,
                metadataUrl);
        return new McpError(HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.InvalidRequest, "The access token is invalid or expired.");
    }

    /**
     * Sets the {@code WWW-Authenticate} response header.
     *
     * @param response the servlet response
     * @param error the RFC 6750 {@code error} value, or {@code null} to omit it (and
     *            {@code errorDescription})
     * @param errorDescription the RFC 6750 {@code error_description} value; ignored when
     *            {@code error} is {@code null}
     * @param scopes the scopes to advertise in the {@code scope} auth-param; omitted when empty
     * @param metadataUrl the RFC 9728 {@code resource_metadata} URL; omitted when {@code null}
     */
    private void setChallenge(final HttpServletResponse response, final String error, final String errorDescription,
            final Set<String> scopes, final String metadataUrl) {
        final StringBuilder challenge = new StringBuilder("Bearer realm=\"").append(REALM).append('"');
        if (error != null) {
            challenge.append(", error=\"").append(error).append('"');
            if (errorDescription != null) {
                challenge.append(", error_description=\"").append(errorDescription).append('"');
            }
        }
        if (scopes != null && !scopes.isEmpty()) {
            challenge.append(", scope=\"").append(String.join(" ", scopes)).append('"');
        }
        if (metadataUrl != null) {
            challenge.append(", resource_metadata=\"").append(metadataUrl).append('"');
        }
        response.setHeader(WWW_AUTHENTICATE, challenge.toString());
    }

    /**
     * Builds the JWKS-backed source once per JWKS URI, then delegates to
     * {@link #newProcessor(JWKSource)} for the actual processor assembly.
     * <p>
     * This split exists specifically so a test can exercise the <em>real</em> assembly logic
     * (key selector, algorithm restriction, claims verifier) without ever needing a real JWKS
     * URL: {@link #newProcessor(JWKSource)} takes the source as a parameter, so a test can pass
     * an in-process {@code ImmutableJWKSet} straight to it. Before this split, every test
     * replaced this class's {@link #getProcessor()} wholesale with a hand-maintained duplicate
     * of this method's configuration, which meant this method itself -- the one that ships --
     * was never executed by anything.
     * </p>
     *
     * @param jwksUri the JWKS endpoint
     * @return a configured processor, per {@link #newProcessor(JWKSource)}
     * @throws MalformedURLException if {@code jwksUri} is not a valid URL
     */
    protected ConfigurableJWTProcessor<SecurityContext> newProcessor(final String jwksUri) throws MalformedURLException {
        final JWKSource<SecurityContext> source = JWKSourceBuilder.<SecurityContext> create(new URL(jwksUri))
                .cache(getJwksCacheSeconds() * 1000L, JWKS_CACHE_REFRESH_TIMEOUT_MILLIS)
                .refreshAheadCache(true)
                .build();
        return newProcessor(source);
    }

    /**
     * Assembles a JWT processor from an already-built {@link JWKSource}.
     * <p>
     * The real assembly logic: restricts signature verification to RS256 against {@code source},
     * and attaches {@link #newClaimsVerifier()}. Deliberately independent of how {@code source}
     * was obtained -- {@link #newProcessor(String)} builds a network-backed one via
     * {@link JWKSourceBuilder}, a test builds an in-process {@code ImmutableJWKSet} -- so this
     * method itself, the one place the key selector, the RS256 restriction, and the claims
     * verifier are actually wired together, is exercised identically by both.
     * </p>
     *
     * @param source the JWK source to verify signatures against
     * @return a configured processor: RS256-only signature verification against {@code source},
     *         and a claims verifier that requires (and, if present, time-checks) {@code exp}
     */
    protected ConfigurableJWTProcessor<SecurityContext> newProcessor(final JWKSource<SecurityContext> source) {
        final ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
        processor.setJWTClaimsSetVerifier(newClaimsVerifier());
        return processor;
    }

    /**
     * Builds the {@code exp}/{@code nbf} claims verifier {@link #newProcessor(JWKSource)}
     * attaches to the processor.
     * <p>
     * Extracted into its own method -- rather than inlined at its one call site -- so it can
     * also be unit-tested directly against a bare {@link JWTClaimsSet}, with no {@link JWKSource}
     * or network involved, independent of whichever {@link #newProcessor(JWKSource)} caller is
     * under test.
     * </p>
     *
     * @return a verifier that requires {@code exp} to be present (and, per
     *         {@link DefaultJWTClaimsVerifier}, then automatically checks it against the current
     *         time with its default clock-skew tolerance); {@code nbf} is checked when present
     *         but not required, since not every authorization server issues it
     */
    protected JWTClaimsSetVerifier<SecurityContext> newClaimsVerifier() {
        return new DefaultJWTClaimsVerifier<>(null, Set.of(JWTClaimNames.EXPIRATION_TIME));
    }

    /**
     * Returns the JWT processor, building and caching it on first use via
     * {@link #newProcessor(String)}.
     * <p>
     * This is the seam a container-free test overrides to reach {@link #newProcessor(JWKSource)}
     * -- the real assembly logic (key selector, RS256 restriction, claims verifier) -- with an
     * in-process key source (e.g. {@code ImmutableJWKSet}), instead of
     * {@link #newProcessor(String)}'s network-backed {@link JWKSourceBuilder}. Only the source's
     * origin differs between a test and production; the assembly a test exercises is the same
     * method production runs.
     * </p>
     *
     * @return the JWT processor, shared across every request this instance authenticates
     */
    protected ConfigurableJWTProcessor<SecurityContext> getProcessor() {
        ConfigurableJWTProcessor<SecurityContext> processor = cachedProcessor;
        if (processor == null) {
            synchronized (this) {
                processor = cachedProcessor;
                if (processor == null) {
                    try {
                        processor = newProcessor(getJwksUri());
                    } catch (final MalformedURLException e) {
                        throw new IllegalStateException("mcp.oauth.jwks.uri is not a valid URL: " + getJwksUri(), e);
                    }
                    cachedProcessor = processor;
                }
            }
        }
        return processor;
    }

    /**
     * Returns the configured JWKS endpoint.
     *
     * @return {@code mcp.oauth.jwks.uri}'s value; blank when unset
     */
    protected String getJwksUri() {
        return getSystemProperty("mcp.oauth.jwks.uri", StringUtil.EMPTY);
    }

    /**
     * Returns the configured JWKS cache lifetime.
     *
     * @return {@code mcp.oauth.jwks.cache.seconds}'s value; {@value #DEFAULT_JWKS_CACHE_SECONDS} when unset
     */
    protected int getJwksCacheSeconds() {
        return getSystemPropertyAsInt("mcp.oauth.jwks.cache.seconds", DEFAULT_JWKS_CACHE_SECONDS);
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
     * Returns the configured audience override.
     *
     * @return {@code mcp.oauth.audience}'s value; blank means "derive from the request"
     */
    protected String getConfiguredAudience() {
        return getSystemProperty("mcp.oauth.audience", StringUtil.EMPTY);
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
     * Returns the required scopes.
     *
     * @return the parsed {@code mcp.oauth.required.scopes}; empty when unset, meaning no scope
     *         is required
     */
    protected Set<String> getRequiredScopes() {
        return parseScopeList(getSystemProperty("mcp.oauth.required.scopes", StringUtil.EMPTY));
    }

    /**
     * Returns the configured permission claim name.
     *
     * @return {@code mcp.oauth.permission.claim}'s value; blank means this source contributes no
     *         permissions
     */
    protected String getPermissionClaim() {
        return getSystemProperty("mcp.oauth.permission.claim", StringUtil.EMPTY);
    }

    /**
     * Returns the configured scope-to-permission map.
     * <p>
     * Parsed from a comma-separated list of {@code scope=permission} pairs, e.g.
     * {@code "fess:search=Rguest,fess:search=1guest,fess:admin=Radmin-api"}; a scope repeated
     * across multiple pairs accumulates every one of its mapped permissions. A malformed pair
     * (no {@code '='}, or an empty scope or permission) is skipped rather than rejecting the
     * whole property.
     * </p>
     *
     * @return the parsed map; empty when {@code mcp.oauth.scope.permission.map} is unset
     */
    protected Map<String, Set<String>> getScopePermissionMap() {
        final String raw = getSystemProperty("mcp.oauth.scope.permission.map", StringUtil.EMPTY);
        final Map<String, Set<String>> map = new LinkedHashMap<>();
        if (StringUtil.isBlank(raw)) {
            return map;
        }
        for (final String pair : raw.split(",")) {
            final String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                continue;
            }
            final String scope = trimmed.substring(0, eq).trim();
            final String permission = trimmed.substring(eq + 1).trim();
            if (scope.isEmpty() || permission.isEmpty()) {
                continue;
            }
            map.computeIfAbsent(scope, k -> new LinkedHashSet<>()).add(permission);
        }
        return map;
    }

    /**
     * Parses a comma-separated scope-list configuration value into a trimmed, order-preserving
     * set.
     * <p>
     * {@code public static} -- rather than a {@code private} helper duplicated wherever a
     * comma-separated scope list needs parsing -- specifically so {@code McpMetadataApiManager
     * #getScopesSupported()} can parse {@code mcp.oauth.required.scopes} through this exact
     * method too: that property is read by both classes (this one to enforce it, that one to
     * advertise it), and a single parser is the only way to guarantee they can never silently
     * diverge on what counts as a valid scope token.
     * </p>
     *
     * @param raw the raw value
     * @return the parsed set; empty when {@code raw} is blank
     */
    public static Set<String> parseScopeList(final String raw) {
        if (StringUtil.isBlank(raw)) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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

    /**
     * Reads an int-valued Fess system property.
     *
     * @param key the system property key
     * @param defaultValue the value to return when the property is unset
     * @return the property's value, or {@code defaultValue} when unset
     */
    protected int getSystemPropertyAsInt(final String key, final int defaultValue) {
        return ComponentUtil.getFessConfig().getSystemPropertyAsInt(key, defaultValue);
    }
}
