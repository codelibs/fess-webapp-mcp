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
 * independently refuse to reach this branch with a blank {@code configuredAudience} -- {@code
 * OAuthResourceServerAuthenticator#authenticate} refuses before ever calling {@link #resolve},
 * and both it (via {@code isUsable()}) and {@code McpMetadataApiManager#process} apply {@link
 * #isUsableConfiguration} before their own separate call to {@link #resolve}, so the audience
 * requirement itself is defined exactly once even though it is still enforced at two independent
 * call sites. This branch is kept, rather than deleted, because it remains a directly and
 * thoroughly tested (see {@code CanonicalResourceUriTest}), pure, well-isolated piece of the
 * trust-boundary logic Fess's own {@code TargetOriginResolver} also needs for the same {@code
 * X-Forwarded-*} headers -- deleting it would not make the codebase any safer (the vulnerability
 * was never in this method; it was in calling it without first requiring a configured audience),
 * only harder to directly verify in isolation. Any future caller of {@link #resolve} with a blank
 * {@code configuredAudience} for a security decision must independently justify why deriving from
 * the request is safe in its context -- the answer for an OAuth audience check is that it is not.
 * </p>
 * <p>
 * <b>Honest cost of keeping it.</b> With both callers now required to hold a non-blank audience
 * before this class is even usable, {@code trustedProxies} and the entire {@code X-Forwarded-*}
 * overlay it gates are, as of this wave, never reached by either caller in production either --
 * only {@link #resolve}'s first branch (the configured-audience short-circuit) ever runs. That is
 * roughly half of this class's own source by line count. It stays for the same reason the derived
 * branch itself stays: it is not wrong, it is thoroughly tested in isolation, and removing it buys
 * no additional safety over the guards that already make it unreachable. Follow-up, not a blocker.
 * </p>
 */
public final class CanonicalResourceUri {

    /** The literal MCP endpoint path. Never derived from a request. */
    static final String MCP_PATH = "/mcp";

    /**
     * The well-known metadata path {@link #metadataUrl} substitutes for a resource identifier's
     * trailing {@link #MCP_PATH}, keeping anything that precedes it.
     */
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
     * Builds the well-known protected-resource metadata URL for {@code canonicalUri}, by
     * replacing its trailing {@link #MCP_PATH} with {@link #WELL_KNOWN_SUFFIX} and keeping
     * everything before it.
     * <p>
     * For a root-context deployment ({@code https://host/mcp}) this produces {@code
     * https://host/.well-known/oauth-protected-resource/mcp}, which is exactly the URL RFC 9728
     * &#xa7;3.1 prescribes. For a deployment under a non-root context path -- Fess's {@code
     * FESS_CONTEXT_PATH} / {@code -Dfess.context.path}, or a reverse proxy mounting Fess at a
     * subpath -- the resource identifier is {@code https://host/api/mcp}, and this method keeps
     * the {@code /api} prefix: {@code https://host/api/.well-known/oauth-protected-resource/mcp}.
     * </p>
     * <p>
     * <b>That is a deliberate, and largely forced, deviation from RFC 9728 &#xa7;3.1.</b> The
     * &#xa7;3.1 construction inserts the well-known path between the <em>host</em> and the
     * resource's path, which for the example above would be {@code
     * https://host/.well-known/oauth-protected-resource/api/mcp} -- host-rooted, and therefore
     * outside the servlet context Fess is mounted in. Nothing in this plugin, or in Fess, can
     * serve a URL above its own context path, so advertising the &#xa7;3.1 form under a subpath
     * deployment would advertise a URL that is guaranteed to 404. The prefix-preserving form
     * <em>is</em> served: {@code McpMetadataApiManager#matches} compares
     * {@code request.getServletPath()}, which excludes the context path, so {@code
     * https://host/api/.well-known/oauth-protected-resource/mcp} arrives there as the literal
     * {@code /.well-known/oauth-protected-resource/mcp} it matches on. The two forms coincide
     * whenever the context path is the root, so this deviation is visible only to subpath
     * deployments.
     * </p>
     * <p>
     * <b>Interop caveat.</b> A client that follows the {@code resource_metadata} URL this server
     * advertises in its {@code WWW-Authenticate} challenge (and in the {@code resource} field's
     * own document) -- the discovery flow RFC 9728 and the MCP Authorization specification both
     * direct clients to use -- always reaches the right document. A client that instead
     * <em>constructs</em> the &#xa7;3.1 URL itself from the resource identifier will get a 404
     * under a non-root context path. That trade is accepted knowingly: the alternative is a URL
     * that 404s for every client rather than only for clients that ignore the advertised one.
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
     * {@code resource_metadata} shape this server actually serves: it must <em>end in</em> the
     * literal {@link #MCP_PATH} segment.
     * <p>
     * <b>Ending in {@code /mcp} is the whole requirement -- leading path segments are supported
     * and expected.</b> {@link #metadataUrl} replaces only the trailing {@code /mcp}, preserving
     * whatever precedes it, and {@code McpMetadataApiManager#matches} compares
     * {@code request.getServletPath()}, which excludes the context path. So {@code
     * https://host/api/mcp} -- the correct RFC 8707 resource identifier for a Fess deployed under
     * the context path {@code /api} ({@code FESS_CONTEXT_PATH} / {@code -Dfess.context.path}), or
     * behind a reverse proxy mounting it at a subpath -- yields {@code
     * https://host/api/.well-known/oauth-protected-resource/mcp}, which that manager <em>does</em>
     * serve. Such a value is compatible, and this method must keep accepting it.
     * </p>
     * <p>
     * <b>Do not tighten this to an exact-path match.</b> It has been reported twice as a bug that
     * this accepts {@code https://host/api/mcp}; it is not one, in either direction. Rejecting it
     * would make every context-path deployment's {@code oauth} configuration "unusable", and the
     * consequence of "unusable" here is not a loud failure but
     * {@code McpApiManager#getAuthenticator} falling back to {@code NoneAuthenticator} -- i.e.
     * silently serving {@code /mcp} anonymously to a deployment that had configured OAuth
     * correctly. Turning a correct configuration into an authentication bypass is far worse than
     * the 404-on-a-mistyped-audience this check exists to prevent. README's {@code
     * mcp.oauth.audience} entries and {@code McpApiManager}'s startup ERROR string both state the
     * same "must end in {@code /mcp}" contract; all four must be changed together or not at all.
     * </p>
     * <p>
     * What is actually excluded is a value ending anywhere <em>else</em> -- {@code
     * https://host/api/mcp2}, {@code https://host}, {@code urn:fess-resource} -- for which
     * {@link #metadataUrl} produces a URL nothing serves, so a caller must refuse to treat that
     * configuration as usable rather than silently pointing every challenge at a 404.
     * </p>
     *
     * @param audience the raw {@code mcp.oauth.audience} configuration value; blank is always
     *            compatible, since the derived (non-configured) case in {@link #resolve} always
     *            ends in {@code /mcp} by construction
     * @return {@code true} when {@code audience} is blank or, once normalised, ends with the
     *         literal {@code /mcp} path segment -- with or without further path segments before
     *         it
     */
    public static boolean isCompatibleAudience(final String audience) {
        return StringUtil.isBlank(audience) || normalize(audience).endsWith(MCP_PATH);
    }

    /**
     * Returns whether an {@code oauth}-mode configuration is usable at all: {@code issuer} is
     * configured, {@code audience} is configured and {@link #isCompatibleAudience(String)
     * compatible}, and {@code jwksUri} is configured.
     * <p>
     * {@code OAuthResourceServerAuthenticator#isUsable()} and {@code McpMetadataApiManager
     * #process} both need this exact three-way check -- the first to decide whether to select
     * this authenticator at all, the second (reading its own independent {@code ComponentUtil}
     * seams, not consulting the authenticator instance) to decide whether to serve the RFC 9728
     * metadata document. Factored out here, alongside {@link #resolve} and {@link #metadataUrl},
     * for the same reason those are: two classes in different packages need the identical pure
     * computation, and a single shared implementation is the only way to guarantee they cannot
     * silently drift apart. Before this method existed, they already had, twice: the audience
     * requirement and the {@code jwksUri} requirement were each added to one class's hand-written
     * checks first and had to be separately, manually mirrored into the other's.
     * </p>
     *
     * @param issuer the {@code mcp.oauth.issuer} value
     * @param audience the {@code mcp.oauth.audience} value; blank means "not configured" here --
     *            unlike {@link #resolve}'s {@code configuredAudience} parameter, this method has
     *            no request to fall back to deriving from
     * @param jwksUri the {@code mcp.oauth.jwks.uri} value
     * @return {@code true} when all three are non-blank and {@code audience} is compatible
     */
    public static boolean isUsableConfiguration(final String issuer, final String audience, final String jwksUri) {
        return StringUtil.isNotBlank(issuer) && StringUtil.isNotBlank(audience) && isCompatibleAudience(audience)
                && StringUtil.isNotBlank(jwksUri);
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
