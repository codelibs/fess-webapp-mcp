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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.util.ComponentUtil;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
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

    /**
     * The smallest JWKS cache lifetime, in seconds, {@link #newProcessor(String)} can actually
     * build a source for; a smaller configured value is clamped up to this by
     * {@link #getJwksCacheSeconds()}. See that method's Javadoc for why this exact number.
     */
    private static final int MIN_JWKS_CACHE_SECONDS = 60;

    /** The JWKS cache refresh timeout passed to {@link JWKSourceBuilder#cache(long, long)}, in milliseconds. */
    private static final long JWKS_CACHE_REFRESH_TIMEOUT_MILLIS = 30_000L;

    /**
     * The connect timeout, in milliseconds, for fetching the JWKS document. See
     * {@link #newJwksRetriever()} for why {@link JWKSourceBuilder}'s own default is not used.
     */
    private static final int JWKS_CONNECT_TIMEOUT_MILLIS = 3_000;

    /**
     * The read timeout, in milliseconds, for fetching the JWKS document. See
     * {@link #newJwksRetriever()} for why {@link JWKSourceBuilder}'s own default is not used.
     */
    private static final int JWKS_READ_TIMEOUT_MILLIS = 3_000;

    /**
     * The maximum accepted JWKS document size, in bytes (256 KiB). See {@link #newJwksRetriever()}
     * for why {@link JWKSourceBuilder}'s own default is not used.
     */
    private static final int JWKS_SIZE_LIMIT_BYTES = 262_144;

    /** The RFC 9068 &#xa7;4 media type for a JWT access token, as a {@code typ} header value. */
    private static final JOSEObjectType ACCESS_TOKEN_JWT_TYPE = new JOSEObjectType("at+jwt");

    /** RFC 9068 &#xa7;4's equally-mandated long form of {@link #ACCESS_TOKEN_JWT_TYPE}. */
    private static final JOSEObjectType ACCESS_TOKEN_JWT_TYPE_LONG_FORM = new JOSEObjectType("application/at+jwt");

    /**
     * The lazily-built, cached JWT processor together with the JWKS-source configuration it was
     * built from. Built on the first request that actually presents a bearer token -- never at
     * construction, so a container-free test that never calls {@link #authenticate} (or that
     * overrides {@link #getProcessor()} directly) never touches {@link #newProcessor(String)},
     * network I/O, or {@code ComponentUtil} -- and rebuilt whenever that configuration changes.
     * <p>
     * A <em>single</em> field holding both halves, rather than one field per half, so
     * {@link #getProcessor()}'s unsynchronised fast path reads a processor and the configuration it
     * belongs to in one atomic volatile read. With two separate volatile fields, a concurrent
     * rebuild could be observed half-applied -- an old processor paired with the new
     * configuration's key -- and the stale processor would then be accepted as current.
     * </p>
     */
    private volatile CachedProcessor cachedProcessor;

    /**
     * A built JWT processor bound to the {@code (jwksUri, cacheSeconds)} pair it was built from.
     *
     * @param jwksUri the {@code mcp.oauth.jwks.uri} value {@link #processor} fetches keys from
     * @param cacheSeconds the <em>effective</em> (already clamped by {@link #getJwksCacheSeconds()})
     *            key-cache lifetime {@link #processor} was built with
     * @param processor the processor itself
     */
    private record CachedProcessor(String jwksUri, int cacheSeconds, ConfigurableJWTProcessor<SecurityContext> processor) {

        /**
         * Returns whether this cache entry was built from exactly the given configuration.
         *
         * @param currentJwksUri the currently configured {@code mcp.oauth.jwks.uri}
         * @param currentCacheSeconds the currently effective key-cache lifetime
         * @return {@code true} when this entry is still current and may be reused
         */
        boolean matches(final String currentJwksUri, final int currentCacheSeconds) {
            return cacheSeconds == currentCacheSeconds && jwksUri.equals(currentJwksUri);
        }
    }

    /**
     * Guards {@link #getJwksCacheSeconds()}'s clamp WARN so it is emitted at most once per
     * instance. The clamp itself is unconditional; only the log line is rate-limited, so an
     * operator sees the misconfiguration without it repeating for the life of the process.
     */
    private final AtomicBoolean jwksCacheFloorWarned = new AtomicBoolean();

    /**
     * Creates an {@code oauth}-mode authenticator. Stateless at construction time: every
     * {@code ComponentUtil} read and the JWKS processor are built lazily, on first use.
     */
    public OAuthResourceServerAuthenticator() {
        // no state
    }

    /**
     * Returns whether this authenticator is usable: {@code mcp.oauth.issuer} is configured,
     * {@code mcp.oauth.audience} is configured and compatible with the fixed
     * {@code resource_metadata} shape this server actually serves, and {@code mcp.oauth.jwks.uri}
     * is configured.
     * <p>
     * RFC 9728 requires a protected-resource metadata document's {@code authorization_servers}
     * to be non-empty; serving one with none would be worse than not enabling authorization at
     * all. Separately, {@link CanonicalResourceUri#metadataUrl} builds the well-known URL by
     * replacing a trailing {@code /mcp} with the fixed well-known suffix, so the audience must
     * <em>end in</em> the {@code /mcp} path segment for the advertised {@code resource_metadata}
     * URL to be one {@code McpMetadataApiManager} actually serves. Leading path segments are fine
     * and expected -- {@code https://host/api/mcp} is the correct audience for a Fess deployed
     * under the context path {@code /api} -- because the well-known URL is built under that same
     * prefix and {@code matches()} compares {@code getServletPath()}, which excludes the context
     * path. Only a value ending somewhere other than {@code /mcp} (e.g. {@code
     * https://host/api/mcp2}) is rejected. See {@link CanonicalResourceUri#isCompatibleAudience}.
     * </p>
     * <p>
     * <b>{@code mcp.oauth.audience} is required, not merely validated when present (C1).</b>
     * Without an explicitly configured audience, {@link #resolveCanonicalUri} would derive the
     * RFC 8707 resource identifier {@link #requireAudience} checks a token's {@code aud} against
     * from the request's {@code Host} header (or, from a trusted proxy, {@code
     * X-Forwarded-Host}) -- a value the caller controls on any direct request. An attacker
     * holding a token legitimately issued by the same authorization server, but minted for a
     * <em>different</em> resource, could then simply send that resource's hostname as {@code
     * Host} and be admitted: the derived audience would equal the token's real {@code aud},
     * satisfying {@link #requireAudience} for a resource this deployment never intended to
     * accept it for. This is exactly the confused-deputy scenario RFC 8707 audience binding
     * exists to prevent. Requiring an operator-pinned audience keeps that derivation out of the
     * trust decision entirely -- see {@link CanonicalResourceUri}'s class Javadoc for the
     * resulting reachability of its derived-URI branch.
     * </p>
     * <p>
     * <b>{@code mcp.oauth.jwks.uri} is required too (I1).</b> Without it, this authenticator
     * would still be selected, and the first token-bearing request would reach {@link
     * #getProcessor()} -&gt; {@link #newProcessor(String)} -&gt; {@code new URL("")} -&gt; a
     * caught {@link java.net.MalformedURLException} reshaped into an {@link IllegalStateException}
     * -&gt; {@link #authenticate}'s generic catch clause, which reports it as an ordinary invalid
     * token. That 401 is misleading (nothing about the caller's token is wrong) and, unlike the
     * two checks above, would previously fire with <em>no</em> startup diagnostic at all -- ERROR
     * fires only when this method returns {@code false}, and a blank {@code jwks.uri} did not
     * make it do so.
     * </p>
     * <p>
     * {@code McpApiManager#getAuthenticator} consults this method directly before selecting this
     * class, falling back to {@code none}-mode behaviour when it returns {@code false}.
     * {@code McpMetadataApiManager} does <em>not</em> consult this instance method -- it has none
     * of this class's state to call it on, reading {@code mcp.auth.mode}/{@code mcp.oauth.*}
     * through its own, independent {@code ComponentUtil} seams instead -- but it applies the
     * exact same {@link CanonicalResourceUri#isUsableConfiguration} predicate this method
     * delegates to, falling back to HTTP 404 when it returns {@code false}. The predicate is
     * shared so the two classes' definitions of "usable" cannot silently drift apart the way
     * their hand-written, independently-duplicated checks already had before {@code
     * isUsableConfiguration} existed.
     * </p>
     *
     * @return {@code true} when {@link #getIssuer()}, {@link #getConfiguredAudience()}, and
     *         {@link #getJwksUri()} are all non-blank and
     *         {@link CanonicalResourceUri#isCompatibleAudience} accepts
     *         {@link #getConfiguredAudience()} -- see {@link CanonicalResourceUri#isUsableConfiguration}
     */
    public boolean isUsable() {
        return CanonicalResourceUri.isUsableConfiguration(getIssuer(), getConfiguredAudience(), getJwksUri());
    }

    @Override
    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
        final Set<String> requiredScopes = getRequiredScopes();
        if (StringUtil.isBlank(getConfiguredAudience())) {
            // C1 defence in depth: never reach resolveCanonicalUri's request-derived branch for
            // an oauth-mode authentication decision. See #audienceNotConfigured.
            throw audienceNotConfigured(response, requiredScopes);
        }
        final String canonicalUri = resolveCanonicalUri(request);
        final String metadataUrl = CanonicalResourceUri.metadataUrl(canonicalUri);
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
     * Builds the 401 to throw when this authenticator is invoked with no configured
     * {@code mcp.oauth.audience}.
     * <p>
     * C1: without a configured audience, {@link #resolveCanonicalUri} would derive the RFC 8707
     * resource identifier from the request's caller-controlled {@code Host} header (or, from a
     * trusted proxy, {@code X-Forwarded-Host}) instead -- exactly the value audience binding
     * exists to keep out of this decision. Refusing here, before {@link #resolveCanonicalUri} is
     * even called, keeps that derivation (and the {@code resource_metadata} hint it would
     * otherwise feed into the challenge below) out of the response entirely: {@code
     * metadataUrl} is passed as {@code null} deliberately, not derived and then discarded.
     * </p>
     * <p>
     * No {@code error} (or {@code error_description}) is set, for the same RFC 6750 &#xa7;3
     * reason {@link #missingCredential} sets none: this check runs before {@link #authenticate}
     * has looked at the {@code Authorization} header at all, so it cannot yet know whether the
     * caller supplied a credential -- {@code error="invalid_token"} specifically claims one was
     * supplied and rejected, which would misdescribe the caller's own request in exactly the case
     * where no credential was sent either.
     * </p>
     * <p>
     * In production this method is only ever reached on an instance {@code
     * McpApiManager#getAuthenticator} has already confirmed {@link #isUsable()} for -- and
     * {@link #isUsable()} now requires a non-blank audience too -- so this check is a redundant,
     * fail-closed backstop against a future or test caller that invokes {@link #authenticate}
     * directly, bypassing that selection gate.
     * </p>
     *
     * @param response the servlet response to attach the challenge header to
     * @param requiredScopes the configured {@code mcp.oauth.required.scopes}
     * @return the error to throw; never returns normally
     */
    private McpError audienceNotConfigured(final HttpServletResponse response, final Set<String> requiredScopes) {
        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] oauth authentication refused: mcp.oauth.audience is not configured");
        }
        setChallenge(response, null, null, requiredScopes, null);
        return new McpError(HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.InvalidRequest,
                "This server's OAuth configuration is incomplete.");
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
     * <p>
     * The cache lifetime comes from {@link #getJwksCacheSeconds()}, which clamps a too-small
     * configured value rather than letting {@link JWKSourceBuilder} reject it: nimbus enforces a
     * lower bound on the TTL relative to the refresh-ahead window and the refresh timeout passed
     * here, and violating it throws an unchecked exception this class would otherwise report to
     * the caller as an invalid token. See {@link #getJwksCacheSeconds()}.
     * </p>
     *
     * <p>
     * The retrieval limits come from {@link #newJwksRetriever()} rather than from
     * {@link JWKSourceBuilder}'s own defaults, and {@code retrying(true)} makes a single transient
     * fetch failure survivable. See {@link #newJwksRetriever()} for why both matter.
     * </p>
     *
     * @param jwksUri the JWKS endpoint
     * @return a configured processor, per {@link #newProcessor(JWKSource)}
     * @throws MalformedURLException if {@code jwksUri} is not a valid URL
     */
    protected ConfigurableJWTProcessor<SecurityContext> newProcessor(final String jwksUri) throws MalformedURLException {
        final JWKSource<SecurityContext> source = JWKSourceBuilder.<SecurityContext> create(new URL(jwksUri), newJwksRetriever())
                .cache(getJwksCacheSeconds() * 1000L, JWKS_CACHE_REFRESH_TIMEOUT_MILLIS)
                .refreshAheadCache(true)
                .retrying(true)
                .build();
        return newProcessor(source);
    }

    /**
     * Builds the HTTP retriever {@link #newProcessor(String)} fetches the JWKS document with.
     * <p>
     * <b>Passing one explicitly is the point.</b> {@link JWKSourceBuilder#create(URL)} -- the
     * single-argument overload -- supplies a retriever built from {@code JWKSourceBuilder}'s
     * <em>own</em> constants: a 500 ms connect timeout, a 500 ms read timeout, and a 50 KB size
     * limit. Those are not {@link DefaultResourceRetriever}'s defaults (its no-argument
     * constructor means unlimited, {@code 0/0/0}); they are deliberately tight values chosen for a
     * library that cannot know its caller's network, and 500 ms is comfortably inside the time a
     * cold TLS handshake to a real authorization server can take.
     * </p>
     * <p>
     * <b>Why a single slow fetch is not a single slow request.</b> The chain
     * {@link #newProcessor(String)} builds puts a rate limiter in front of the JWKS endpoint
     * ({@code RateLimitedJWKSetSource}, 30 s minimum interval). A fetch that times out consumes
     * that window, so the next 30 seconds of token-bearing requests fail immediately -- reported,
     * as always, as a 401 {@code invalid_token} that blames the caller's perfectly good token,
     * with the real cause reaching the log only at DEBUG (see {@link #invalidToken}). Against an
     * endpoint chronically slower than 500 ms this never recovers at all. Raising the timeouts to
     * {@value #JWKS_CONNECT_TIMEOUT_MILLIS} ms and adding {@code retrying(true)} keeps a slow or
     * momentarily-flaky authorization server from becoming an outage for every MCP client.
     * </p>
     * <p>
     * <b>{@code outageTolerant(...)} was considered and deliberately not used.</b> It would serve
     * keys from a stale cache for a configured window after the JWKS endpoint goes away, which
     * extends the lifetime of a revoked or rotated signing key beyond what the operator's
     * {@code mcp.oauth.jwks.cache.seconds} says. That is a change in security posture, not a
     * robustness tweak, so it is left to a future change that asks for it explicitly.
     * </p>
     * <p>
     * The limits are fixed constants rather than {@code mcp.oauth.*} properties on purpose: they
     * are bounds on this server's own HTTP client, not a description of the authorization server,
     * and no deployment has been observed to need different ones. The size limit is raised anyway
     * ({@value #JWKS_SIZE_LIMIT_BYTES} bytes) because it costs nothing: a typical JWKS is 2-8 KB,
     * so 50 KB is rarely the binding constraint, but an authorization server publishing a large
     * set of rotated keys should not silently become unverifiable.
     * </p>
     *
     * @return a retriever with this class's own connect timeout, read timeout, and size limit
     */
    protected DefaultResourceRetriever newJwksRetriever() {
        return new DefaultResourceRetriever(JWKS_CONNECT_TIMEOUT_MILLIS, JWKS_READ_TIMEOUT_MILLIS, JWKS_SIZE_LIMIT_BYTES);
    }

    /**
     * Assembles a JWT processor from an already-built {@link JWKSource}.
     * <p>
     * The real assembly logic: restricts signature verification to RS256 against {@code source},
     * and attaches {@link #newClaimsVerifier()}. Deliberately independent of how {@code source}
     * was obtained -- {@link #newProcessor(String)} builds a network-backed one via
     * {@link JWKSourceBuilder}, a test builds an in-process {@code ImmutableJWKSet} -- so this
     * method itself, the one place the key selector, the RS256 restriction, the {@code typ}
     * verifier, and the claims verifier are actually wired together, is exercised identically by
     * both.
     * </p>
     *
     * <p>
     * <b>The {@code typ} header (RFC 9068 &#xa7;4).</b> Nimbus's default when nothing is set is
     * {@code DefaultJOSEObjectTypeVerifier.JWT}, which permits only {@code typ: JWT} or an absent
     * {@code typ} -- so a JWT access token carrying the very {@code typ} RFC 9068 &#xa7;4 says a
     * resource server "MUST verify" ({@code at+jwt}, or its long form {@code application/at+jwt})
     * would be rejected outright, with a 401 blaming the caller's token. This method therefore
     * installs a verifier allowing {@code at+jwt}, {@code application/at+jwt}, {@code JWT}, and
     * (the {@code null} entry) an absent {@code typ}.
     * </p>
     * <p>
     * <b>This widening does not weaken anything.</b> The obvious worry is ID-token confusion, but
     * an OIDC ID Token carries {@code typ: JWT} or no {@code typ} at all -- both of which the
     * previous default <em>already</em> accepted -- so that exposure is unchanged by adding two
     * further values no ID Token uses. What actually keeps an ID Token out is
     * {@link #requireIssuer} plus {@link #requireAudience}: an ID Token's {@code aud} is the
     * client's {@code client_id}, never this server's canonical resource URI, so it fails RFC 8707
     * audience binding regardless of its {@code typ}. Going the other way and accepting
     * <em>only</em> {@code at+jwt} is not viable in practice: Entra ID, Auth0, Okta, and a
     * default-configured Keycloak all mint access tokens with {@code typ: JWT}, so a strict
     * verifier would reject the majority of real deployments' tokens. Types outside this set --
     * e.g. {@code ID_TOKEN} or {@code secevent+jwt} -- remain rejected. Matching is
     * case-insensitive ({@link com.nimbusds.jose.JOSEObjectType#equals} compares ignoring case).
     * </p>
     *
     * @param source the JWK source to verify signatures against
     * @return a configured processor: RS256-only signature verification against {@code source}, an
     *         RFC 9068-compatible {@code typ} verifier, and a claims verifier that requires (and,
     *         if present, time-checks) {@code exp}
     */
    protected ConfigurableJWTProcessor<SecurityContext> newProcessor(final JWKSource<SecurityContext> source) {
        final ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
        // The trailing null is what keeps an absent typ acceptable; see this method's Javadoc.
        processor.setJWSTypeVerifier(
                new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, ACCESS_TOKEN_JWT_TYPE, ACCESS_TOKEN_JWT_TYPE_LONG_FORM, null));
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
     * <p>
     * <b>The cache is keyed on the configuration, not merely on "has it been built yet".</b>
     * {@code mcp.oauth.jwks.uri} and {@code mcp.oauth.jwks.cache.seconds} are Fess system
     * properties an operator edits at runtime, exactly like {@code mcp.oauth.issuer},
     * {@code mcp.oauth.audience}, {@code mcp.oauth.required.scopes},
     * {@code mcp.oauth.permission.claim} and {@code mcp.oauth.scope.permission.map}, all of which
     * this class already re-reads on every request. These two are the only ones consumed solely at
     * <em>build</em> time, inside {@link #newProcessor(String)}, so a build-once cache silently
     * froze them: after an edit, the processor stayed bound to the previous JWKS endpoint and TTL
     * for the life of the JVM. The failure mode is deceptively quiet -- every token-bearing request
     * fails against the old key set and is reported as an ordinary 401 {@code invalid_token} (see
     * {@link #invalidToken}, which logs the real cause at DEBUG only), while {@link #isUsable()}
     * keeps reporting the configuration as fine because it only tests non-blankness, so not even an
     * authentication-mode change is logged. Restarting Fess was the only cure. <b>Do not simplify
     * this back to a null check.</b>
     * </p>
     * <p>
     * Equally deliberately, it is not rebuilt unconditionally either: this runs on the per-request
     * hot path, and a rebuild discards the entire cached JWK set, so an unkeyed rebuild would turn
     * every request into a fresh JWKS fetch. The key uses the <em>clamped</em>
     * {@link #getJwksCacheSeconds()} value, not the raw property, so two differing sub-floor
     * settings that produce the same effective lifetime do not count as a change. A failed build is
     * never cached: {@link #cachedProcessor} is assigned only after {@link #newProcessor(String)}
     * returns, so correcting a malformed {@code mcp.oauth.jwks.uri} recovers on the very next
     * request.
     * </p>
     *
     * @return the JWT processor for the current JWKS configuration, shared across every request
     *         this instance authenticates until that configuration changes
     */
    protected ConfigurableJWTProcessor<SecurityContext> getProcessor() {
        final String jwksUri = getJwksUri();
        final int cacheSeconds = getJwksCacheSeconds();
        CachedProcessor cached = cachedProcessor;
        if (cached == null || !cached.matches(jwksUri, cacheSeconds)) {
            synchronized (this) {
                cached = cachedProcessor;
                if (cached == null || !cached.matches(jwksUri, cacheSeconds)) {
                    try {
                        cached = new CachedProcessor(jwksUri, cacheSeconds, newProcessor(jwksUri));
                    } catch (final MalformedURLException e) {
                        throw new IllegalStateException("mcp.oauth.jwks.uri is not a valid URL: " + jwksUri, e);
                    }
                    cachedProcessor = cached;
                }
            }
        }
        return cached.processor();
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
     * Returns the JWKS cache lifetime to use, clamped up to {@value #MIN_JWKS_CACHE_SECONDS}
     * seconds when {@code mcp.oauth.jwks.cache.seconds} is configured below that floor.
     * <p>
     * <b>Why {@value #MIN_JWKS_CACHE_SECONDS} specifically.</b> {@link #newProcessor(String)}
     * builds the source with {@code .cache(ttl, }{@value #JWKS_CACHE_REFRESH_TIMEOUT_MILLIS}{@code
     * ).refreshAheadCache(true)}. Two of nimbus's own invariants then bound the TTL from below:
     * </p>
     * <ul>
     * <li>{@code RefreshAheadCachingJWKSetSource} rejects a TTL smaller than the sum of its
     * refresh-ahead window ({@code JWKSourceBuilder.DEFAULT_REFRESH_AHEAD_TIME}, 30s) and the
     * cache refresh timeout (30s here) -- i.e. a TTL below 60s throws
     * {@link IllegalArgumentException}; and</li>
     * <li>{@code JWKSourceBuilder#build()} rejects a TTL that is not <em>strictly</em> greater
     * than the rate limiter's minimum request interval ({@code
     * JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL}, 30s) -- i.e. a TTL of 30s or less throws
     * {@link IllegalStateException}.</li>
     * </ul>
     * <p>
     * Both are unchecked and both escape {@link #getProcessor()} (which catches only
     * {@link MalformedURLException}) into {@link #authenticate}'s broad {@code RuntimeException}
     * clause, where they are laundered into an ordinary 401 {@code invalid_token} -- on
     * <em>every</em> request, forever, because {@link #cachedProcessor} is only assigned after a
     * successful build, so each request retries the same failing build. Nothing about the caller's
     * token is wrong, and the real cause reaches the log only at DEBUG (see {@link #invalidToken}).
     * Clamping turns that permanently-broken deployment into a working one with a slightly longer
     * key-cache lifetime than the operator asked for.
     * </p>
     * <p>
     * <b>Deliberately not an {@link #isUsable()} check.</b> {@code isUsable()} returning
     * {@code false} makes {@code McpApiManager#getAuthenticator} fall back to
     * {@link NoneAuthenticator} -- anonymous access. Treating a too-small cache lifetime as
     * "unusable" would convert a fail-closed misconfiguration into an authentication bypass, which
     * is strictly worse than the 401 it would replace. A bad TTL must never disable
     * authentication, so this method clamps and continues instead.
     * </p>
     *
     * @return the effective JWKS cache lifetime in seconds: {@code mcp.oauth.jwks.cache.seconds}'s
     *         value when it is at least {@value #MIN_JWKS_CACHE_SECONDS},
     *         {@value #MIN_JWKS_CACHE_SECONDS} when it is smaller, and
     *         {@value #DEFAULT_JWKS_CACHE_SECONDS} when the property is unset
     */
    protected int getJwksCacheSeconds() {
        final int configured = getSystemPropertyAsInt("mcp.oauth.jwks.cache.seconds", DEFAULT_JWKS_CACHE_SECONDS);
        if (configured >= MIN_JWKS_CACHE_SECONDS) {
            return configured;
        }
        if (logger.isWarnEnabled() && jwksCacheFloorWarned.compareAndSet(false, true)) {
            logger.warn(
                    "[MCP] mcp.oauth.jwks.cache.seconds={} is below the minimum supported value - using {} instead. "
                            + "The JWKS cache lifetime must leave room for both the 30s refresh-ahead window and the 30s cache "
                            + "refresh timeout, and must be strictly longer than the 30s rate-limiter interval; a smaller value "
                            + "makes the JWKS source fail to build and every token-bearing request return a misleading 401.",
                    configured, MIN_JWKS_CACHE_SECONDS);
        }
        return MIN_JWKS_CACHE_SECONDS;
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
     * Required for {@link #isUsable()} (C1): a blank value now makes this authenticator unusable,
     * and {@link #authenticate} refuses every call outright (see {@link #audienceNotConfigured})
     * rather than deriving a canonical URI from the request the way {@link CanonicalResourceUri
     * #resolve}'s own {@code configuredAudience} parameter still documents as its general
     * contract -- that derivation is retained in {@link CanonicalResourceUri} itself but is no
     * longer reachable from this class with a blank value here.
     * </p>
     *
     * @return {@code mcp.oauth.audience}'s value; blank means oauth mode is not configured
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
