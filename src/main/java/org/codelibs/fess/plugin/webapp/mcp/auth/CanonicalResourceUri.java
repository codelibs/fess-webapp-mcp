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
     * Returns the first value of a possibly comma-separated header value, trimmed, or
     * {@code null} when the input is blank.
     *
     * @param headerValue the raw header value
     * @return the first value, trimmed, or {@code null}
     */
    private static String firstValue(final String headerValue) {
        if (StringUtil.isBlank(headerValue)) {
            return null;
        }
        final int comma = headerValue.indexOf(',');
        final String first = comma >= 0 ? headerValue.substring(0, comma) : headerValue;
        final String trimmed = first.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
