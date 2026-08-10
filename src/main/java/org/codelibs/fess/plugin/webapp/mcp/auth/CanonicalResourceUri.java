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

import java.util.Locale;
import java.util.Set;

import org.codelibs.core.lang.StringUtil;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the single canonical resource URI shared by OAuth 2.1 audience validation, the RFC
 * 9728 protected-resource metadata document, and the {@code resource_metadata} URL attached to
 * a {@code WWW-Authenticate} challenge.
 * <p>
 * Factored out of {@code OAuthResourceServerAuthenticator} and {@code McpMetadataApiManager} --
 * two classes in different packages that both need this exact value -- so the derivation exists
 * in exactly one place. Each caller still reads its own configuration through its own
 * {@code ComponentUtil}-backed seam (so each stays independently container-free testable); only
 * the pure computation lives here, as a static method with no {@code ComponentUtil} dependency of
 * its own.
 * </p>
 * <p>
 * Resolution order:
 * </p>
 * <ol>
 * <li>{@code configuredAudience}, when non-blank, normalised (fragment stripped, trailing slash
 * stripped) and returned as-is otherwise. This is the operator's explicit override and is never
 * combined with anything derived from the request.</li>
 * <li>Otherwise, {@code <scheme>://<host>[:port]/mcp} built from the request's observed
 * scheme/host/port -- or, when {@code request.getRemoteAddr()} is a member of
 * {@code trustedProxies}, from the {@code X-Forwarded-Proto}/{@code X-Forwarded-Host}/
 * {@code X-Forwarded-Port} headers instead. Forwarded headers from a caller that is not a
 * trusted proxy are never consulted, matching the trust rule Fess's own
 * {@code TargetOriginResolver} uses for the same headers.</li>
 * </ol>
 * <p>
 * The {@code /mcp} suffix in the derived case is always the literal path segment, never the
 * request's own {@code getServletPath()}: {@code McpApiManager#matches} accepts both {@code /mcp}
 * and any {@code /mcp/*} sub-path, so a path-derived audience would differ per sub-path and a
 * token bound to {@code https://host/mcp} would be rejected at {@code https://host/mcp/x}.
 * </p>
 * <p>
 * <b>Item 2 (the request-derived branch) is retained, but is unreachable-by-contract from either
 * production caller (C1).</b> Deriving a security-critical resource identifier from {@code
 * request.getServerName()} -- the {@code Host} header, caller-controlled on any direct request --
 * is unsound for an RFC 8707 audience decision no matter how carefully the trusted-proxy overlay
 * above is scoped: an attacker who simply sends the {@code Host} of a <em>different</em> resource
 * served by the same authorization server, while holding a token legitimately minted for that
 * other resource, would make this branch derive an audience that matches the token's real {@code
 * aud} -- the confused-deputy case audience binding exists to prevent. Both current callers now
 * refuse to reach this branch with a blank {@code configuredAudience}, each with its own
 * independent guard rather than relying on the other's: {@code
 * OAuthResourceServerAuthenticator#authenticate} refuses before ever calling {@link #resolve},
 * and {@code OAuthResourceServerAuthenticator#isUsable} (consulted by {@code
 * McpApiManager#getAuthenticator} before this authenticator is even selected) requires a
 * non-blank audience too; {@code McpMetadataApiManager#process} independently refuses the same
 * way before its own call to {@link #resolve}. This branch is kept, rather than deleted, because
 * it remains a directly and thoroughly tested (see {@code CanonicalResourceUriTest}), pure,
 * well-isolated piece of the trust-boundary logic Fess's own {@code TargetOriginResolver} also
 * needs for the same {@code X-Forwarded-*} headers -- deleting it would not make the codebase any
 * safer (the vulnerability was never in this method; it was in calling it without first requiring
 * a configured audience), only harder to directly verify in isolation. Any future caller of
 * {@link #resolve} with a blank {@code configuredAudience} for a security decision must
 * independently justify why deriving from the request is safe in its context -- the answer for
 * an OAuth audience check is that it is not.
 * </p>
 */
public final class CanonicalResourceUri {

    /** The literal MCP endpoint path. Never derived from a request. */
    static final String MCP_PATH = "/mcp";

    /** The RFC 9728 well-known path suffix, for a resource whose path is {@link #MCP_PATH}. */
    private static final String WELL_KNOWN_SUFFIX = "/.well-known/oauth-protected-resource/mcp";

    /** The {@code https} default port. */
    private static final int HTTPS_DEFAULT_PORT = 443;

    /** The {@code http} default port. */
    private static final int HTTP_DEFAULT_PORT = 80;

    private CanonicalResourceUri() {
        // static utility only
    }

    /**
     * Resolves the canonical resource URI for {@code request}.
     *
     * @param request the servlet request; consulted only when {@code configuredAudience} is
     *            blank
     * @param configuredAudience the {@code mcp.oauth.audience} value; blank means "derive from
     *            the request"
     * @param trustedProxies the configured {@code rate.limit.trusted.proxies} set; forwarded
     *            headers are honoured only when {@code request.getRemoteAddr()} is a member
     * @return the canonical resource URI: no fragment, no trailing slash
     */
    public static String resolve(final HttpServletRequest request, final String configuredAudience, final Set<String> trustedProxies) {
        if (StringUtil.isNotBlank(configuredAudience)) {
            return normalize(configuredAudience);
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        final String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && trustedProxies != null && trustedProxies.contains(remoteAddr)) {
            final String forwardedProto = firstValue(request.getHeader("X-Forwarded-Proto"));
            final String forwardedHost = firstValue(request.getHeader("X-Forwarded-Host"));
            if (StringUtil.isNotBlank(forwardedProto) && StringUtil.isNotBlank(forwardedHost)) {
                scheme = forwardedProto;
                final int split = hostPortSplit(forwardedHost);
                if (split >= 0) {
                    host = forwardedHost.substring(0, split);
                    port = parsePort(forwardedHost.substring(split + 1), defaultPort(scheme));
                } else {
                    host = forwardedHost;
                    final String forwardedPort = firstValue(request.getHeader("X-Forwarded-Port"));
                    port = StringUtil.isNotBlank(forwardedPort) ? parsePort(forwardedPort, defaultPort(scheme)) : defaultPort(scheme);
                }
            }
        }
        return normalize(buildOrigin(scheme, host, port) + MCP_PATH);
    }

    /**
     * Builds the RFC 9728 well-known metadata URL for {@code canonicalUri}.
     * <p>
     * RFC 9728 &#xa7;3.1 constructs the metadata URL by inserting {@code
     * /.well-known/oauth-protected-resource} between the resource identifier's host and its
     * path. Since this server's resource path is always the literal {@link #MCP_PATH}, that
     * insertion collapses to stripping a trailing {@code /mcp} (when present) and appending the
     * fixed suffix.
     * </p>
     *
     * @param canonicalUri the canonical resource URI, as returned by {@link #resolve}
     * @return the well-known metadata URL
     */
    public static String metadataUrl(final String canonicalUri) {
        final String prefix =
                canonicalUri.endsWith(MCP_PATH) ? canonicalUri.substring(0, canonicalUri.length() - MCP_PATH.length()) : canonicalUri;
        return prefix + WELL_KNOWN_SUFFIX;
    }

    /**
     * Returns whether a configured {@code mcp.oauth.audience} value is compatible with the one
     * {@code resource_metadata} shape this server actually serves.
     * <p>
     * {@link #metadataUrl} only implements RFC 9728 &#xa7;3.1's well-known-path insertion for a
     * resource path of exactly {@code /mcp} -- the one shape {@code McpMetadataApiManager}
     * matches, since widening its exact-match {@code matches()} to an unbounded set of paths
     * would conflict with the very guarantee that method exists to give (registration order
     * across plugins is not deterministic, so a broad match risks shadowing something else). A
     * configured audience whose path is not {@code /mcp} -- e.g. {@code
     * https://host/api/mcp} -- would make {@link #metadataUrl} produce a URL nothing serves, so
     * a caller must refuse to treat that configuration as usable rather than silently pointing
     * every challenge at a 404.
     * </p>
     *
     * @param audience the raw {@code mcp.oauth.audience} configuration value; blank is always
     *            compatible, since the derived (non-configured) case in {@link #resolve} always
     *            ends in {@code /mcp} by construction
     * @return {@code true} when {@code audience} is blank or, once normalised, ends with the
     *         literal {@code /mcp} path segment
     */
    public static boolean isCompatibleAudience(final String audience) {
        return StringUtil.isBlank(audience) || normalize(audience).endsWith(MCP_PATH);
    }

    /**
     * Strips a fragment and any trailing slashes from {@code raw}.
     *
     * @param raw the URI text to normalise
     * @return the normalised URI
     */
    private static String normalize(final String raw) {
        String value = raw.trim();
        final int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        while (value.length() > 1 && value.charAt(value.length() - 1) == '/') {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * Builds {@code scheme://host[:port]}, lower-casing scheme and host and omitting the port
     * when it is the scheme's default.
     *
     * @param scheme the URI scheme
     * @param host the host
     * @param port the port; {@code <= 0} or the scheme's default port is omitted
     * @return the origin
     */
    private static String buildOrigin(final String scheme, final String host, final int port) {
        final String safeScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        final String safeHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        final StringBuilder buf = new StringBuilder();
        buf.append(safeScheme).append("://").append(safeHost);
        if (port > 0 && port != defaultPort(safeScheme)) {
            buf.append(':').append(port);
        }
        return buf.toString();
    }

    /**
     * Returns the scheme's default port, or {@code -1} for a scheme this class does not
     * recognise.
     *
     * @param scheme the URI scheme, matched case-insensitively
     * @return the default port, or {@code -1}
     */
    private static int defaultPort(final String scheme) {
        if ("https".equalsIgnoreCase(scheme)) {
            return HTTPS_DEFAULT_PORT;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return HTTP_DEFAULT_PORT;
        }
        return -1;
    }

    /**
     * Finds the {@code ':'} that separates host from port in an {@code X-Forwarded-Host} value,
     * respecting a bracketed IPv6 literal (e.g. {@code [::1]:8443}), where the inner colons of
     * the address must not be mistaken for the port separator.
     *
     * @param host the forwarded host value, already known non-blank
     * @return the index of the separating {@code ':'}, or {@code -1} when the host carries no
     *         port
     */
    private static int hostPortSplit(final String host) {
        if (host.startsWith("[")) {
            final int closing = host.lastIndexOf(']');
            if (closing >= 0 && closing + 1 < host.length() && host.charAt(closing + 1) == ':') {
                return closing + 1;
            }
            return -1;
        }
        return host.indexOf(':');
    }

    /**
     * Parses a port number, falling back to {@code fallback} when {@code raw} is not a valid
     * port.
     *
     * @param raw the text to parse
     * @param fallback the value to return when parsing fails
     * @return the parsed port, or {@code fallback}
     */
    private static int parsePort(final String raw, final int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Returns the first value of a possibly comma-separated header value, or {@code null} when
     * the input is blank or, once the first value is isolated, embeds a control character.
     * <p>
     * Deliberately strips only surrounding ASCII spaces, not {@link String#trim()}: {@code trim()}
     * also silently removes trailing CR/LF/TAB, which would let a header value such as
     * {@code "attacker.example.com\r\nX-Injected: evil"} slip through with its trailing CRLF
     * stripped and its embedded one intact -- and this value can end up directly inside the
     * {@code WWW-Authenticate} response header via {@code resource_metadata}. Any remaining
     * embedded whitespace or control character is rejected outright (the whole value is
     * discarded, not truncated at the bad character), the same way {@code OriginUtil#canonicalize}
     * treats the same class of input for the same {@code X-Forwarded-*} trust boundary.
     * </p>
     *
     * @param headerValue the raw header value
     * @return the first value, or {@code null} when blank, empty after stripping, or carrying an
     *         embedded control character
     */
    private static String firstValue(final String headerValue) {
        if (StringUtil.isBlank(headerValue)) {
            return null;
        }
        final int comma = headerValue.indexOf(',');
        final String first = comma >= 0 ? headerValue.substring(0, comma) : headerValue;
        final String stripped = stripAsciiSpaces(first);
        if (stripped.isEmpty() || containsWhitespaceOrControlChar(stripped)) {
            return null;
        }
        return stripped;
    }

    /**
     * Strips only surrounding ASCII space ({@code ' '}) characters -- not {@link String#trim()},
     * whose broader definition of whitespace would silently remove a trailing CR/LF/TAB instead
     * of leaving it for {@link #containsWhitespaceOrControlChar} to reject.
     *
     * @param value the text to strip
     * @return {@code value} with leading/trailing ASCII spaces removed
     */
    private static String stripAsciiSpaces(final String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Returns whether {@code value} contains any whitespace or ISO control character, anywhere
     * in the string.
     *
     * @param value the text to scan
     * @return {@code true} when a whitespace or control character is present
     */
    private static boolean containsWhitespaceOrControlChar(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c <= ' ' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }
}
