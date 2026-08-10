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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

import org.codelibs.fess.app.service.AccessTokenService;
import org.codelibs.fess.exception.InvalidAccessTokenException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.util.ComponentUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@code mcp.auth.mode=fess_token} strategy: requires an RFC 6750 bearer credential that
 * names a valid, unexpired Fess access token, and resolves the caller's permissions from that
 * token's stored (encoded) permission list.
 * <p>
 * Two hazards this class exists specifically to avoid:
 * </p>
 * <ol>
 * <li>Fess's own {@code AccessTokenHelper.getAccessTokenFromRequest} compares the
 * {@code Authorization} scheme with case-sensitive {@code String.equals("Bearer")}, violating
 * RFC 6750's requirement that the scheme be matched case-insensitively -- and a non-canonical
 * case such as {@code "bearer xyz"} does not merely fail to match, it makes that method
 * <em>throw</em> {@code InvalidAccessTokenException}. This class never calls that method for
 * extraction; {@link #extractBearerToken(String)} does the case-insensitive match itself.</li>
 * <li>{@code AccessTokenService#getPermissions(HttpServletRequest)} folds in
 * {@code request.getParameterValues(accessToken.getParameterName())} when the token row
 * declares a {@code parameterName}, letting the caller inject arbitrary extra permission
 * strings through the query string. {@link #resolvePermissions} suppresses that channel by
 * presenting {@link AccessTokenService} with a request wrapper whose
 * {@code getParameterValues} always reports nothing supplied.</li>
 * </ol>
 */
public class FessTokenAuthenticator implements McpAuthenticator {

    /** The RFC 6750 bearer scheme, matched case-insensitively. */
    private static final String BEARER_SCHEME = "bearer";

    /** The HTTP response header carrying the RFC 6750 challenge. */
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    /** The {@code Authorization} request header name. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Creates a {@code fess_token}-mode authenticator. Stateless: every instance behaves
     * identically, resolving a fresh {@link AccessTokenService} lookup per call.
     */
    public FessTokenAuthenticator() {
        // no state
    }

    @Override
    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
        final String token = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (token == null) {
            throw unauthorized(response, "A Bearer access token is required.");
        }
        try {
            final Set<String> permissions = resolvePermissions(request, token);
            return new McpPrincipal(subjectFor(token), Set.of(), permissions);
        } catch (final InvalidAccessTokenException e) {
            throw unauthorized(response, e.getMessage());
        }
    }

    @Override
    public boolean ownsRoleResolution() {
        return true;
    }

    /**
     * Resolves the encoded Fess permissions carried by {@code token}.
     * <p>
     * Delegates to {@link AccessTokenService#getPermissions(HttpServletRequest)} -- the same
     * component Fess's own {@code RoleQueryHelper} uses -- through a wrapper request that
     * reports the already-extracted {@code token} as a canonically-cased {@code Authorization}
     * header (so the service's internal, case-sensitive extraction always succeeds) and reports
     * no query parameters (so the query-derived permission channel described in this class's
     * Javadoc contributes nothing). This is the sole {@code ComponentUtil} call this class
     * makes; it is reached only after {@link #extractBearerToken} has already confirmed a
     * bearer credential is present, so a request with no (or a non-bearer) credential never
     * touches the DI container.
     * </p>
     *
     * @param request the original servlet request, forwarded (with header/parameter overrides)
     *            to {@link AccessTokenService}
     * @param token the bearer token to resolve, already extracted and non-blank
     * @return the token's encoded permissions; empty when the token carries none
     * @throws InvalidAccessTokenException when {@code token} does not name a stored access
     *             token, or names one that has expired
     */
    protected Set<String> resolvePermissions(final HttpServletRequest request, final String token) {
        final AccessTokenService accessTokenService = ComponentUtil.getComponent(AccessTokenService.class);
        return accessTokenService.getPermissions(new QueryPermissionsSuppressedRequest(request, token))
                .map(HashSet::new)
                .orElseGet(HashSet::new);
    }

    /**
     * Builds the 401 to throw for a missing or invalid token, attaching an RFC 6750
     * {@code WWW-Authenticate} challenge to {@code response} first.
     * <p>
     * This class never delegates status-setting to the servlet container's own error-page
     * mechanism (see {@code SendErrorProhibitedTest}): Fess maps container-generated error
     * statuses through {@code redirect.jsp}, which turns them into a 302 and discards any header
     * set beforehand, including this challenge.
     * </p>
     *
     * @param response the servlet response to attach the challenge header to
     * @param message a human-readable description of why authentication failed; embedded in
     *            the challenge's {@code error_description} after stripping characters that
     *            could otherwise inject additional header syntax
     * @return the error to throw; never returns normally
     */
    private McpError unauthorized(final HttpServletResponse response, final String message) {
        response.setHeader(WWW_AUTHENTICATE,
                "Bearer realm=\"fess-mcp\", error=\"invalid_token\", error_description=\"" + sanitizeForHeader(message) + "\"");
        return new McpError(HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.InvalidRequest, message);
    }

    /**
     * Strips characters that could split or inject additional syntax into an HTTP header value.
     * <p>
     * {@code message} may echo attacker-controlled input -- {@code InvalidAccessTokenException}
     * messages include the raw bearer token the caller supplied (e.g.
     * {@code "Invalid token: <token>"}) -- so it is not safe to place verbatim into a header
     * value even though the servlet container is also expected to reject a literal CR/LF.
     * </p>
     *
     * @param message the raw message, may be {@code null}
     * @return {@code message} with CR, LF, and {@code "} replaced; never {@code null}
     */
    private static String sanitizeForHeader(final String message) {
        if (message == null) {
            return "";
        }
        return message.replace('\r', ' ').replace('\n', ' ').replace('"', '\'');
    }

    /**
     * Derives a subject identifier for {@link McpPrincipal#getSubject()} that does not itself
     * carry the secret token value.
     * <p>
     * The raw token is a bearer credential -- anything holding it can impersonate the caller --
     * so it must not be copied into a field whose contract does not document it as secret (and
     * from which it could later end up in a log line or a rate-limiter key). A short SHA-256
     * digest is a stable, non-reversible stand-in: the same token always yields the same
     * subject, without exposing the token itself.
     * </p>
     *
     * @param token the bearer token, already extracted and non-blank
     * @return a stable, non-secret subject identifier derived from {@code token}
     */
    private static String subjectFor(final String token) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder("token:");
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandated algorithm (Java SE spec, MessageDigest); unreachable.
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /**
     * Extracts a bearer token, matching the scheme case-insensitively per RFC 6750.
     *
     * @param header the raw {@code Authorization} header value, may be {@code null}
     * @return the token, or {@code null} when the header is absent, uses another scheme, or
     *         names an empty token
     */
    public static String extractBearerToken(final String header) {
        if (header == null) {
            return null;
        }
        final String trimmed = header.trim();
        final int space = trimmed.indexOf(' ');
        if (space < 0) {
            return null;
        }
        if (!BEARER_SCHEME.equalsIgnoreCase(trimmed.substring(0, space))) {
            return null;
        }
        final String token = trimmed.substring(space + 1).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Presents a fixed, canonically-formatted bearer credential to {@link AccessTokenService}
     * while suppressing the query-parameter permission channel described in this class's
     * Javadoc.
     * <p>
     * Package-private rather than {@code private} so {@code AuthenticatorTest} (same package)
     * can construct and exercise it directly, proving the parameter-suppression guarantee
     * without needing the live DI container {@link #resolvePermissions} otherwise requires.
     * </p>
     */
    static final class QueryPermissionsSuppressedRequest extends HttpServletRequestWrapper {

        /** The {@code Authorization} header value reported for every {@link #getHeader} call requesting it. */
        private final String authorizationHeader;

        /**
         * Creates a wrapper.
         *
         * @param request the request to wrap
         * @param token the already-extracted bearer token to report as the credential
         */
        QueryPermissionsSuppressedRequest(final HttpServletRequest request, final String token) {
            super(request);
            this.authorizationHeader = "Bearer " + token;
        }

        @Override
        public String getHeader(final String name) {
            return AUTHORIZATION_HEADER.equalsIgnoreCase(name) ? authorizationHeader : super.getHeader(name);
        }

        @Override
        public String[] getParameterValues(final String name) {
            // AccessTokenService.getPermissions folds in getParameterValues(accessToken
            // .getParameterName()) when the token row declares one, letting the caller inject
            // permissions via the query string. MCP does not adopt that channel, so every name
            // reports "none supplied" here regardless of what the token row declares.
            return null;
        }
    }
}
