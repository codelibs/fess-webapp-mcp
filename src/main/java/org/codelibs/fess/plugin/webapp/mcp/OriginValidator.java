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
package org.codelibs.fess.plugin.webapp.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates the HTTP {@code Origin} header on incoming MCP requests.
 *
 * <p>
 * The MCP Streamable HTTP transport makes Origin validation a server MUST: a request whose
 * {@code Origin} header is present but does not match the server's own origin or a configured
 * allowed origin is rejected with HTTP 403. A request that carries no {@code Origin} header at
 * all is <em>not</em> treated as invalid -- the spec only mandates rejection for a
 * <em>present</em> disallowed value, and non-browser clients (CLI bridges, stdio proxies, curl)
 * never send the header, so requiring it would break every legitimate non-browser MCP client.
 * </p>
 *
 * <p>
 * This class is deliberately self-contained: it does not reuse Fess's {@code originValidator}
 * component, which is specific to {@code /api/v2} and falls back to the {@code Referer} header
 * when {@code Origin} is absent -- a fallback the MCP spec does not call for and that would
 * defeat the "absent Origin passes" rule above.
 * </p>
 *
 * <p>
 * <b>Normalisation:</b> both the request's own ("self") origin and every candidate origin
 * (the incoming {@code Origin} header value and each configured allowed origin) are reduced to
 * {@code scheme://host[:port]} with the scheme and host lowercased and the port omitted when it
 * is the scheme's default (80 for {@code http}, 443 for {@code https}) -- browsers omit the
 * default port when sending {@code Origin}, so a bare comparison would otherwise reject a
 * legitimate same-origin request on the default port. Comparison after normalisation is an
 * exact string match: no wildcard and no suffix matching are performed, and no case-folding is
 * applied beyond the scheme/host lowercasing normalisation defines.
 * </p>
 *
 * <p>
 * <b>Reverse proxies:</b> the self origin is derived only from {@link HttpServletRequest#getScheme()},
 * {@link HttpServletRequest#getServerName()}, and {@link HttpServletRequest#getServerPort()}.
 * This class deliberately does not inspect {@code X-Forwarded-*} or {@code Forwarded} headers --
 * behind a reverse proxy that terminates TLS or rewrites the host/port, those methods reflect
 * what the proxy sends upstream, not the client-facing URL, so a same-origin request arriving
 * through such a proxy may need an explicit entry in the allowed-origins configuration.
 * Forwarded-header-aware canonical-URI derivation is centralised elsewhere (the OAuth
 * resource-metadata work) rather than duplicated here.
 * </p>
 */
public final class OriginValidator {

    private OriginValidator() {
        // no instantiation
    }

    /**
     * Rejects {@code request} when its {@code Origin} header is present and matches neither the
     * request's own origin nor any entry of {@code allowedOrigins}.
     * <p>
     * Only the scheme, host, and port of {@code Origin} are compared -- a path or query string,
     * if one is present, is silently ignored, so {@code https://fess.example.com/anything}
     * matches exactly as {@code https://fess.example.com} would. A conformant browser never
     * sends a path or query on {@code Origin}; this is a byproduct of normalising through
     * {@link java.net.URI} and is called out here so it isn't mistaken for a bypass. A value
     * that fails to parse as a {@code scheme://host} origin at all -- including the literal
     * string {@code "null"} some browsers send for opaque origins, an empty string, or a
     * scheme with no authority -- is treated as present-and-invalid, not absent, so it is
     * rejected the same as a foreign origin rather than silently allowed.
     * </p>
     *
     * @param request the servlet request carrying the candidate {@code Origin} header
     * @param allowedOrigins additional origins to accept beyond the request's own; each entry
     *            is normalised the same way as the {@code Origin} header before comparison, so
     *            scheme/host case and a default port are not significant
     * @throws McpError with HTTP 403 and {@link ErrorCode#InvalidRequest} when {@code Origin} is
     *             present but is neither the self origin nor a member of {@code allowedOrigins}
     */
    public static void validate(final HttpServletRequest request, final Set<String> allowedOrigins) {
        final String origin = request.getHeader("Origin");
        if (origin == null) {
            // Absent is not "invalid" -- only a present, disallowed Origin is rejected.
            return;
        }
        final String normalizedOrigin = normalize(origin);
        if (normalizedOrigin != null && normalizedOrigin.equals(selfOrigin(request))) {
            return;
        }
        if (normalizedOrigin != null && allowedOrigins.stream().map(OriginValidator::normalize).anyMatch(normalizedOrigin::equals)) {
            return;
        }
        throw new McpError(HttpServletResponse.SC_FORBIDDEN, ErrorCode.InvalidRequest, "Origin is not allowed: " + origin);
    }

    /**
     * Builds the request's own origin from its scheme, server name, and server port.
     *
     * @param request the servlet request
     * @return the normalised self origin, e.g. {@code "https://fess.example.com"}
     */
    private static String selfOrigin(final HttpServletRequest request) {
        return normalizeParts(request.getScheme(), request.getServerName(), request.getServerPort());
    }

    /**
     * Parses and normalises an origin string for comparison.
     *
     * @param origin the origin string to normalise, e.g. the {@code Origin} header value or an
     *            entry from {@code mcp.allowed.origins}
     * @return the normalised {@code scheme://host[:port]} string, or {@code null} when
     *         {@code origin} is not a valid {@code scheme://host} origin
     */
    private static String normalize(final String origin) {
        try {
            final URI uri = new URI(origin);
            final String scheme = uri.getScheme();
            final String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            return normalizeParts(scheme, host, uri.getPort());
        } catch (final URISyntaxException e) {
            return null;
        }
    }

    /**
     * Assembles a normalised {@code scheme://host[:port]} string, lowercasing the scheme and
     * host and omitting the port when it is absent ({@code <= 0}) or equal to the scheme's
     * default.
     *
     * @param scheme the URI scheme
     * @param host the host
     * @param port the port, or a non-positive value when none was specified
     * @return the normalised origin string
     */
    private static String normalizeParts(final String scheme, final String host, final int port) {
        final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        final String lowerHost = host.toLowerCase(Locale.ROOT);
        final int defaultPort = "https".equals(lowerScheme) ? 443 : "http".equals(lowerScheme) ? 80 : -1;
        if (port <= 0 || port == defaultPort) {
            return lowerScheme + "://" + lowerHost;
        }
        return lowerScheme + "://" + lowerHost + ":" + port;
    }
}
