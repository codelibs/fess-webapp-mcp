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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.Constants;
import org.codelibs.fess.api.BaseApiManager;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.OriginValidator;
import org.codelibs.fess.plugin.webapp.mcp.RateLimiter;
import org.codelibs.fess.plugin.webapp.mcp.auth.FessTokenAuthenticator;
import org.codelibs.fess.plugin.webapp.mcp.auth.McpAuthenticator;
import org.codelibs.fess.plugin.webapp.mcp.auth.McpPrincipal;
import org.codelibs.fess.plugin.webapp.mcp.auth.NoneAuthenticator;
import org.codelibs.fess.plugin.webapp.mcp.auth.OAuthResourceServerAuthenticator;
import org.codelibs.fess.plugin.webapp.mcp.handler.CompletionHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.DiscoverHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.PromptsGetHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.PromptsListHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.ResourceTemplatesListHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.ResourcesListHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.ResourcesReadHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.ToolsCallHandler;
import org.codelibs.fess.plugin.webapp.mcp.handler.ToolsListHandler;
import org.codelibs.fess.plugin.webapp.mcp.json.Json;
import org.codelibs.fess.plugin.webapp.mcp.protocol.HeaderValidator;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpDispatcher;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpRequest;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpRequestMeta;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpResponseWriter;
import org.codelibs.fess.util.ComponentUtil;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@code McpApiManager} class is the HTTP boundary for the MCP (Model Context Protocol)
 * API, revision 2026-07-28. It extends {@code BaseApiManager} and owns exactly the transport
 * concerns -- method/enablement checks, body-size enforcement, JSON-RPC envelope parsing,
 * request-metadata header validation, and protocol-version negotiation -- before handing a
 * validated {@link McpCallContext} to {@link McpDispatcher}, which routes it to the
 * per-method handler that actually produces a result.
 */
public class McpApiManager extends BaseApiManager {

    private static final Logger logger = LogManager.getLogger(McpApiManager.class);

    /** The server name reported in {@code _meta.serverInfo} on every result. */
    protected static final String SERVER_NAME = "fess-mcp-server";

    /**
     * HTTP 429, Too Many Requests. Not one of the named constants on {@link HttpServletResponse}
     * -- that interface predates RFC 6585, which defined this status -- so it is named here
     * instead of spelled out as a bare literal at the throw site.
     */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /**
     * The JSON-RPC methods {@link #enforceRateLimit} charges a token for: every method that
     * reaches a Fess backend. See that method's Javadoc for why {@code resources/read} belongs
     * here alongside the two the spec names, and why the list methods do not.
     */
    private static final Set<String> RATE_LIMITED_METHODS = Set.of("tools/call", "completion/complete", "resources/read");

    /**
     * {@code mcp.auth.mode}'s default value: authenticate nobody, reject nobody. Named (not a
     * bare literal inside {@link #getAuthMode()}) so a test can assert the actual production
     * default directly -- {@code getAuthMode()} itself is always overridden in container-free
     * tests (it reads {@code ComponentUtil}), so nothing else exercises that literal. {@code
     * public}, not {@code protected}: a test asserting against it lives in a different package
     * and is not a subclass, so {@code protected} visibility would not reach it.
     */
    public static final String AUTH_MODE_NONE = "none";

    /** {@code mcp.auth.mode} value selecting {@link FessTokenAuthenticator}. Same visibility rationale as {@link #AUTH_MODE_NONE}. */
    public static final String AUTH_MODE_FESS_TOKEN = "fess_token";

    /**
     * {@code mcp.auth.mode} value selecting {@link OAuthResourceServerAuthenticator}, subject to
     * that class's own {@link OAuthResourceServerAuthenticator#isUsable()} check. Same
     * visibility rationale as {@link #AUTH_MODE_NONE}.
     */
    public static final String AUTH_MODE_OAUTH = "oauth";

    /**
     * The shared rate limiter, or {@code null} until {@link #getRateLimiter()} builds it on
     * first use. Not {@code final}: building it eagerly in a field initializer would call
     * {@link #getRateLimitPerMinute()}'s {@code ComponentUtil} read for every {@code
     * McpApiManager} construction, including every container-free test.
     */
    private RateLimiter rateLimiter;

    /**
     * Also one of the nine handlers in {@link #dispatcher}; held separately because
     * {@link DiscoverHandler#resolveServerVersion()} is the single seam that resolves this
     * plugin's version for {@link #responseWriter}, so both need the same instance.
     */
    private final DiscoverHandler discoverHandler = new DiscoverHandler();

    /**
     * Routes calls to their per-method handler. The nine handlers are stateless, so one
     * dispatcher instance is safe to share across every request this manager processes.
     */
    private final McpDispatcher dispatcher = new McpDispatcher(
            List.of(discoverHandler, new ToolsListHandler(), new ToolsCallHandler(), new ResourcesListHandler(), new ResourcesReadHandler(),
                    new ResourceTemplatesListHandler(), new PromptsListHandler(), new PromptsGetHandler(), new CompletionHandler()));

    /**
     * Writes every response and stamps this server's identity onto successful results. Built
     * from a plain field-to-field call on the already-constructed {@link #discoverHandler}, not
     * from an overridable instance method of {@code this}, so it carries none of the
     * call-an-overridable-method-from-a-field-initializer hazard a subclass constructor could
     * otherwise trip over.
     */
    private final McpResponseWriter responseWriter = new McpResponseWriter(SERVER_NAME, discoverHandler.resolveServerVersion());

    /**
     * The {@code mcp.auth.mode=none} authenticator. Stateless and free of any {@code
     * ComponentUtil} dependency of its own, so building it here at construction time -- like
     * {@link #dispatcher}, {@link #discoverHandler}, and {@link #responseWriter} above -- costs
     * every container-free test nothing.
     */
    private final McpAuthenticator noneAuthenticator = new NoneAuthenticator();

    /**
     * The {@code mcp.auth.mode=fess_token} authenticator. Stateless at construction time -- it
     * only reaches {@code ComponentUtil} from inside {@link McpAuthenticator#authenticate}, and
     * only once a bearer token has actually been found on the request -- so building it here
     * costs every container-free test nothing either.
     */
    private final McpAuthenticator fessTokenAuthenticator = new FessTokenAuthenticator();

    /**
     * The {@code mcp.auth.mode=oauth} authenticator. Stateless at construction time -- like
     * {@link #fessTokenAuthenticator}, it only reaches {@code ComponentUtil} from inside its own
     * methods -- so building it here costs every container-free test nothing, provided the test
     * does not call {@link #getAuthenticator()}'s real (non-overridden) body with
     * {@code mcp.auth.mode=oauth}. See {@link #getOAuthAuthenticator()}.
     */
    private final OAuthResourceServerAuthenticator oauthAuthenticator = new OAuthResourceServerAuthenticator();

    /**
     * The last {@link AuthState} {@link #getAuthenticator()} resolved, so a change can be told
     * apart from a repeat.
     * <p>
     * {@code null} until either {@link #register()} seeds it at startup or the first request
     * resolves one. An {@link AtomicReference} rather than a plain {@code volatile} field
     * because two concurrent requests must not both report the same transition: whichever one
     * wins {@link AtomicReference#getAndSet} owns the log line. The steady-state cost is a
     * single volatile read (see {@link #noteAuthState}) -- no write, no allocation, no lock --
     * which matters because this is consulted on every single request.
     * </p>
     */
    private final AtomicReference<AuthState> lastAuthState = new AtomicReference<>();

    /**
     * The effective authentication posture {@code mcp.auth.mode} resolves to, as opposed to the
     * raw configured string.
     * <p>
     * Exists because "is this endpoint authenticated?" is not a property of the mode string
     * alone: {@code oauth} means two entirely different things depending on whether
     * {@link OAuthResourceServerAuthenticator#isUsable()} agrees, and one of them is
     * indistinguishable in behaviour from {@code none}. Collapsing the raw string onto these
     * four states is what lets {@link #noteAuthState} report a genuine change in posture and
     * stay silent on a cosmetic one -- a typo'd mode string and the literal {@code none} are the
     * same state, so flipping between them is correctly not worth a log line, while
     * {@code oauth} becoming unusable is.
     * </p>
     */
    protected enum AuthState {

        /** {@link NoneAuthenticator}: the default, and every unrecognised mode string. */
        NONE(false),

        /** {@link FessTokenAuthenticator}: a Fess access token is required. */
        FESS_TOKEN(true),

        /** {@link OAuthResourceServerAuthenticator}, with a configuration it accepts. */
        OAUTH(true),

        /**
         * {@code mcp.auth.mode=oauth} with a configuration {@link
         * OAuthResourceServerAuthenticator#isUsable()} rejects: behaves exactly like
         * {@link #NONE}, but is a distinct state so that falling into it is reported rather
         * than mistaken for an operator deliberately choosing {@code none}.
         */
        OAUTH_UNUSABLE(false);

        /** Whether this state requires a credential of the caller. */
        private final boolean authenticated;

        /**
         * Creates a state.
         *
         * @param authenticated whether this state requires a credential
         */
        AuthState(final boolean authenticated) {
            this.authenticated = authenticated;
        }

        /**
         * Returns whether this state requires a credential of the caller.
         *
         * @return true when callers must authenticate; false when every caller is anonymous
         */
        public boolean isAuthenticated() {
            return authenticated;
        }
    }

    /**
     * Creates a new MCP API manager with the default path prefix "/mcp".
     */
    public McpApiManager() {
        // JSON-RPC endpoint is /mcp/*
        setPathPrefix("/mcp");
    }

    /**
     * Registers this API manager with the WebApiManagerFactory.
     * <p>
     * Also the natural "server startup" moment for the one-time {@code mcp.auth.mode=none} WARN
     * ({@link #warnIfAuthenticationIsDisabled}): this {@code @PostConstruct} callback runs
     * exactly once, when the DI container wires this singleton component in, strictly before
     * the endpoint can accept its first request -- unlike {@link #process}, which runs on every
     * request and would repeat the warning needlessly.
     * </p>
     */
    @PostConstruct
    public void register() {
        if (logger.isInfoEnabled()) {
            logger.info("Load {}", this.getClass().getSimpleName());
        }

        warnIfAuthenticationIsDisabled();
        ComponentUtil.getWebApiManagerFactory().add(this);
    }

    /**
     * Reports the startup authentication posture: a WARN when {@code mcp.auth.mode} resolves to
     * the default, unauthenticated {@code none} behaviour -- and, when {@code mcp.auth.mode=oauth}
     * but {@link OAuthResourceServerAuthenticator#isUsable()} is {@code false}, a more severe
     * ERROR explaining why.
     * <p>
     * The MCP Streamable HTTP transport's Security Considerations say a server SHOULD
     * authenticate every connection. Shipping {@code none} as the default is a deliberate,
     * documented deviation from that SHOULD -- made so every existing Fess deployment keeps
     * working without a config change after upgrading this plugin -- and the WARN is that
     * deviation's runtime acknowledgement. It fires for any value {@link #getAuthenticator()}
     * resolves to {@code none} behaviour for, including an unrecognised mode string and a
     * {@code oauth} mode that is not yet usable -- for that last case specifically, the more
     * severe ERROR fires first: a protected-resource metadata document with no authorization
     * server would be worse than not enabling authorization at all (RFC 9728 requires
     * {@code authorization_servers} to be non-empty), so an operator who intended to turn OAuth
     * on needs to know their configuration was rejected, not just that authentication is off.
     * </p>
     * <p>
     * This also <em>seeds</em> {@link #lastAuthState}, which is the reason it shares
     * {@link #logAuthState} with the per-request path rather than logging inline: without the
     * seed, the first request after startup would resolve the same state from a {@code null}
     * baseline and report it a second time.
     * </p>
     */
    protected void warnIfAuthenticationIsDisabled() {
        final String authMode = getAuthMode();
        final AuthState state = resolveAuthState(authMode);
        logAuthState(state, authMode, lastAuthState.getAndSet(state));
    }

    /**
     * Collapses {@code authMode} (plus, for {@code oauth}, the authenticator's own verdict on
     * its configuration) onto the effective posture it selects.
     * <p>
     * The single resolution both {@link #getAuthenticator()} and
     * {@link #warnIfAuthenticationIsDisabled()} go through. They used to derive it separately --
     * one picking an authenticator, the other re-deriving "is this authenticated?" from the mode
     * string and a second {@code isUsable()} call -- which is exactly the shape that lets a
     * future mode be wired into one and forgotten in the other, so that the endpoint silently
     * stops warning about (or starts wrongly warning about) a posture it actually has.
     * </p>
     *
     * @param authMode the raw {@code mcp.auth.mode} value
     * @return the effective posture; never null
     */
    protected AuthState resolveAuthState(final String authMode) {
        if (AUTH_MODE_FESS_TOKEN.equals(authMode)) {
            return AuthState.FESS_TOKEN;
        }
        if (AUTH_MODE_OAUTH.equals(authMode)) {
            return getOAuthAuthenticator().isUsable() ? AuthState.OAUTH : AuthState.OAUTH_UNUSABLE;
        }
        return AuthState.NONE;
    }

    /**
     * Records the posture this request resolved, and reports it when -- and only when -- it
     * differs from the last one.
     * <p>
     * {@link #getAuthenticator()} re-reads {@code mcp.auth.mode} and the {@code mcp.oauth.*}
     * keys on every request, and Fess's {@code DynamicProperties} re-reads its backing file
     * within seconds of an mtime change. A config edit can therefore flip {@code /mcp} from
     * "401 for everyone" to "200 for anyone" with no restart -- which, before this method
     * existed, produced no log line at all, because the only caller of
     * {@link #warnIfAuthenticationIsDisabled()} is the {@code @PostConstruct} that ran hours
     * earlier. That is precisely the change an operator most needs to see in the log.
     * </p>
     * <p>
     * Reporting it on every request instead would be worse than silence: the WARN is several
     * hundred bytes and this is an unauthenticated endpoint in the very mode being warned about,
     * so a caller could turn it into a disk-filling amplifier. Hence the transition check, and
     * hence its shape -- one volatile read on the steady-state path, with the
     * {@link AtomicReference#getAndSet} write reached only when the state has actually changed.
     * Two requests racing through the same transition are resolved by that {@code getAndSet}:
     * the loser observes the state already recorded and stays quiet.
     * </p>
     *
     * @param state the posture this request resolved
     * @param authMode the raw {@code mcp.auth.mode} value behind it, for the message
     */
    protected void noteAuthState(final AuthState state, final String authMode) {
        if (lastAuthState.get() == state) {
            return;
        }
        final AuthState previous = lastAuthState.getAndSet(state);
        if (previous == state) {
            return;
        }
        logAuthState(state, authMode, previous);
    }

    /**
     * Emits the log line(s) for an authentication posture, shared by the startup path
     * ({@link #warnIfAuthenticationIsDisabled()}) and the runtime-transition path
     * ({@link #noteAuthState}).
     * <p>
     * Isolated behind this seam for the same container-free-testing reason as
     * {@link #getOAuthAuthenticator()}: a test can override it to observe <em>that</em> a
     * transition was reported, and how many times, which is the property
     * {@link #noteAuthState}'s guard exists to provide and which no assertion against a live
     * log appender could establish without wiring one up.
     * </p>
     *
     * @param state the posture now in effect
     * @param authMode the raw {@code mcp.auth.mode} value behind it
     * @param previous the posture previously in effect, or {@code null} when this is the first
     *            resolution (startup, or a first request on a manager whose {@code @PostConstruct}
     *            never ran)
     */
    protected void logAuthState(final AuthState state, final String authMode, final AuthState previous) {
        if (state == AuthState.OAUTH_UNUSABLE && logger.isErrorEnabled()) {
            logger.error("[MCP] mcp.auth.mode=oauth but the configuration is not usable - falling back to none. "
                    + "mcp.oauth.issuer must be set to the authorization server's issuer URL; mcp.oauth.audience must be "
                    + "set (it is REQUIRED for oauth mode -- it is never derived from the request's Host header) and must "
                    + "end in /mcp (the only resource path this server's metadata endpoint serves); and mcp.oauth.jwks.uri "
                    + "must be set to the authorization server's JWKS endpoint.");
        }
        if (!state.isAuthenticated()) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "[MCP] mcp.auth.mode={} - every /mcp caller is treated as anonymous{}. "
                                + "Set mcp.auth.mode=fess_token, or mcp.auth.mode=oauth with mcp.oauth.issuer, "
                                + "mcp.oauth.audience, and mcp.oauth.jwks.uri all set, to require a credential.",
                        authMode, previous == null ? StringUtil.EMPTY : " as of now (it was " + previous + " until this request)");
            }
        } else if (previous != null && logger.isInfoEnabled()) {
            logger.info(
                    "[MCP] mcp.auth.mode={} - /mcp now requires a credential (it was {} until this request). "
                            + "Fess re-reads its properties without a restart, so this took effect on a live endpoint.",
                    authMode, previous);
        }
    }

    @Override
    public boolean matches(final HttpServletRequest request) {
        final String path = request.getServletPath();
        // Exact or sub-path only: startsWith would also match "/mcpfoo".
        // mcp.enabled is deliberately NOT consulted here. Returning false lets the filter
        // chain continue, and the container's 404 goes through redirect.jsp, which
        // sendRedirect()s to an HTML page an MCP client cannot interpret.
        return pathPrefix.equals(path) || path.startsWith(pathPrefix + "/");
    }

    @Override
    public void process(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        final McpResponseWriter writer = getResponseWriter();
        Object id = null;
        boolean hasId = false;
        try {
            if (!isEnabled()) {
                throw new McpError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCode.InternalError,
                        "The MCP endpoint is disabled (mcp.enabled=false)");
            }
            if (!"POST".equalsIgnoreCase(request.getMethod())) {
                // The transport is POST-only; GET and DELETE are legacy Streamable HTTP.
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                response.setHeader("Allow", "POST");
                return;
            }
            writeHeaders(response);
            validateOrigin(request); // Task 10
            final McpPrincipal principal = authenticate(request, response); // Task 13-14

            final String body = readRequestBody(request);
            final McpRequest mcpRequest = McpRequest.parse(Json.parseObject(body));
            hasId = mcpRequest.hasId();
            id = mcpRequest.getId();

            // Notifications carry no _meta and no metadata headers. This check MUST precede
            // both, or every conformant notification would be rejected with a 400.
            if (mcpRequest.isNotification()) {
                writer.writeAccepted(response);
                return;
            }

            // A method MCP 2026-07-28 retired must be answered here, BEFORE header validation:
            // a client old enough to still call initialize or ping sends neither
            // MCP-Protocol-Version nor Mcp-Method (Mcp-Method did not exist before this
            // revision), so leaving it to McpDispatcher would answer every such caller with
            // -32020 "MCP-Protocol-Version is required" and make the retired-method diagnostic
            // -- which exists for exactly those clients -- unreachable by them. Deliberately
            // after the notification check above: that ordering is spec-relevant (a conformant
            // notification carries no _meta and no headers) and must not regress. The dispatcher
            // keeps the same branch for a modern client that calls a retired method with valid
            // headers, and owns the error's definition so the two sites cannot drift.
            if (getDispatcher().isRetired(mcpRequest.getMethod())) {
                throw McpDispatcher.methodNotFound(mcpRequest.getMethod());
            }

            HeaderValidator.requirePresent(request, mcpRequest);
            final McpRequestMeta meta = McpRequestMeta.parse(mcpRequest.getParams());
            HeaderValidator.requireMatches(request, mcpRequest, meta);
            requireSupportedVersion(meta.getProtocolVersion());

            final McpCallContext context = new McpCallContext(mcpRequest, meta, mcpRequest.getParams(), principal);
            enforceRateLimit(request, context); // Task 11
            writer.writeResult(response, id, getDispatcher().dispatch(context));
        } catch (final McpError e) {
            writer.writeError(response, id, hasId, e);
        } catch (final Throwable t) {
            // An escaping throwable becomes a container 500, which redirect.jsp turns into
            // a 302 to an HTML page. Everything must be converted here.
            logger.warn("[MCP] Unhandled error: error={}", t.getMessage(), t);
            writer.writeError(response, id, hasId, new McpError(HttpServletResponse.SC_OK, ErrorCode.InternalError, "internal error"));
        }
    }

    /**
     * Reads the request body, refusing anything over {@link #getRequestMaxBytes()} -- without
     * ever buffering more than that.
     * <p>
     * The read and the limit are deliberately the same operation. Reading the body in full and
     * <em>then</em> comparing its size against the limit is not a limit at all: it lets an
     * attacker-chosen body size decide the allocation, and on this endpoint that is reachable
     * unauthenticated in the default {@code mcp.auth.mode=none}, ahead of
     * {@link #enforceRateLimit}. Splitting the check back out into a separate "bounded" wrapper
     * around an unbounded read would silently reintroduce that; if this method ever needs to
     * grow a seam, the bound must move with the read, not stay behind.
     * </p>
     * <p>
     * {@code readNBytes(max + 1)} is the whole mechanism: one byte past the limit is enough to
     * prove a body is over it, and is therefore the largest allocation an oversized body can
     * provoke. {@code request.getContentLength()} is deliberately <em>not</em> consulted as a
     * shortcut -- it is a caller-supplied header, absent entirely for a chunked body, so
     * trusting it would either reject honest chunked callers or be trivially understated by a
     * dishonest one. The stream itself is the only trustworthy source of the real size.
     * </p>
     * <p>
     * The comparison is on bytes, not characters: a multi-byte UTF-8 body has more bytes than
     * {@code String.length()} would report, so decoding first and measuring the {@code String}
     * would let a body of up to three times the limit through. Nothing is decoded until the
     * byte count is known to be within the limit, so the {@code String} is always built from a
     * complete body and can never be a mid-character truncation.
     * </p>
     *
     * @param request the HTTP servlet request
     * @return the request body as a string
     * @throws IOException if an I/O error occurs while reading the request
     * @throws McpError with HTTP 413 when the body exceeds {@code mcp.request.max.bytes}
     */
    protected String readRequestBody(final HttpServletRequest request) throws IOException {
        final int max = getRequestMaxBytes();
        final byte[] bytes = request.getInputStream().readNBytes(probeSize(max));
        if (bytes.length > max) {
            throw new McpError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.InvalidRequest,
                    "request body exceeds mcp.request.max.bytes (" + max + ")");
        }
        return new String(bytes, Constants.UTF_8);
    }

    /**
     * Returns how many bytes {@link #readRequestBody} may read to decide whether {@code max} has
     * been exceeded: one more than the limit, clamped at both ends.
     * <p>
     * Exists only to keep {@code max + 1} from being wrong for the two misconfigured extremes,
     * both of which {@code readNBytes} would answer with an {@code IllegalArgumentException} or
     * an overflowed negative count rather than an HTTP 413. A negative {@code max} probes one
     * byte (any body at all then exceeds it, which is what the old read-everything-first code
     * did too); {@code Integer.MAX_VALUE} probes {@code Integer.MAX_VALUE}, since no
     * {@code byte[]} can exceed that and the limit is therefore unreachable either way.
     * </p>
     *
     * @param max the configured limit in bytes
     * @return the number of bytes to read, always at least 1 and never negative
     */
    private static int probeSize(final int max) {
        if (max >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(max, 0) + 1;
    }

    /**
     * Rejects a protocol version this server does not implement.
     *
     * @param requested the version the client declared
     * @throws McpError with HTTP 400 and -32022 carrying both supported and requested
     */
    protected void requireSupportedVersion(final String requested) {
        if (!McpConstants.SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("supported", List.copyOf(McpConstants.SUPPORTED_PROTOCOL_VERSIONS));
            data.put("requested", requested);
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.UnsupportedProtocolVersion, "Unsupported protocol version",
                    data);
        }
    }

    /**
     * Rejects a request whose Origin header is present but not allowed.
     * <p>
     * The presence check happens here, before {@link #getAllowedOrigins()} runs, so a request
     * with no {@code Origin} header -- every CLI bridge, stdio proxy, and non-browser client --
     * never touches the DI container to build a {@link Set} it would not have consulted anyway.
     * {@link OriginValidator#validate} carries its own {@code null} check too, as defence in
     * depth, but that one alone would still pay for {@link #getAllowedOrigins()} on every
     * request since Java evaluates a method argument before the call.
     * </p>
     *
     * @param request the servlet request
     * @throws McpError with HTTP 403 when the Origin is present and invalid
     */
    protected void validateOrigin(final HttpServletRequest request) {
        if (request.getHeader("Origin") == null) {
            return;
        }
        OriginValidator.validate(request, getAllowedOrigins());
    }

    /**
     * Returns the configured additional allowed origins.
     *
     * @return the allowed origins; empty means no browser origin is allowed at all -- including
     *         the server's own, since {@link OriginValidator} deliberately has no self-origin
     *         branch (it could only be derived from the caller-controlled {@code Host} header)
     */
    protected Set<String> getAllowedOrigins() {
        final String value = ComponentUtil.getFessConfig().getSystemProperty("mcp.allowed.origins", StringUtil.EMPTY);
        if (StringUtil.isBlank(value)) {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /**
     * Consumes a rate-limit token for methods that do real work.
     * <p>
     * There is no seam for this at pipeline step 4 (ahead of body parsing, alongside
     * {@link #validateOrigin}): the JSON-RPC method name -- which methods this limit applies to
     * and which it does not -- is only known once the body has been parsed and dispatched to,
     * so token consumption has to happen here, immediately before
     * {@link McpDispatcher#dispatch}, not earlier in {@link #process}.
     * </p>
     * <p>
     * {@link #RATE_LIMITED_METHODS} is the exact set. {@code tools/call} and
     * {@code completion/complete} are there per the spec's server MUST. {@code resources/read}
     * is there because it is the same backend work under a different method name, not because
     * the spec names it: reading {@code fess://document/<id>} makes the identical
     * {@code SearchHelper#getDocumentByDocId} call the rate-limited {@code get_document} tool
     * makes, and reading {@code fess://index/stats} runs a live cluster/JVM stats collection.
     * Leaving it out made {@code Mcp-Method: resources/read} an unlimited, unauthenticated
     * document-fetch channel that simply bypassed the limit on {@code tools/call} -- so any
     * future method that reaches a backend belongs in that set too, whether or not the spec
     * mentions it.
     * </p>
     * <p>
     * {@code server/discover} and the {@code *&#47;list} methods stay excluded: those really are
     * metadata reads, answered from this plugin's own static descriptions and (for the list
     * methods) cached. {@code resources/read} was never in that category and was only ever
     * grouped with them by proximity of name.
     * </p>
     * <p>
     * {@link #getRateLimiter()} -- and therefore {@link #getRateLimitPerMinute()}'s
     * {@code ComponentUtil} read -- is only reached once the method check above has passed, so a
     * {@code server/discover} or list-method call never touches the DI container here, matching
     * the same early-return discipline {@link #validateOrigin} uses for {@code getAllowedOrigins()}.
     * </p>
     *
     * @param request the servlet request, consulted for the caller's IP when there is no
     *            authenticated subject
     * @param context the call context, supplying the resolved JSON-RPC method
     * @throws McpError with HTTP 429 and a {@code retryAfterSeconds} data entry when the caller
     *             identified by {@link #resolveRateLimitKey} is over the limit
     */
    protected void enforceRateLimit(final HttpServletRequest request, final McpCallContext context) {
        final String method = context.getRequest().getMethod();
        if (!RATE_LIMITED_METHODS.contains(method)) {
            return;
        }
        final RateLimiter limiter = getRateLimiter();
        final String key = resolveRateLimitKey(request, context);
        if (!limiter.tryAcquire(key)) {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("retryAfterSeconds", limiter.getRetryAfterSeconds());
            throw new McpError(HTTP_TOO_MANY_REQUESTS, ErrorCode.InternalError, "rate limit exceeded (mcp.rate.limit.per.minute)", data);
        }
    }

    /**
     * Resolves the identity {@link #enforceRateLimit} counts calls against.
     * <p>
     * Keys on the authenticated subject when {@link #resolvePrincipalSubject} resolves one, the
     * caller's IP otherwise. A {@code null} IP -- not expected from a real container, but
     * possible from a test double -- falls back to the literal {@code "unknown"} rather than a
     * {@code null} key, since {@code ConcurrentHashMap} (which {@link RateLimiter} is built on)
     * rejects {@code null} keys outright.
     * </p>
     * <p>
     * The IP comes from {@link #resolveClientIp}, not from {@code request.getRemoteAddr()}
     * directly. In the default {@code mcp.auth.mode=none} every caller is anonymous, so the
     * subject is always {@code null} and the IP is the <em>only</em> thing separating callers --
     * and behind nginx or Apache {@code getRemoteAddr()} is the proxy's address for every one of
     * them. Fess ships no {@code RemoteIpValve}, so that collapse is the default deployment,
     * not an edge case: one caller spending the per-minute budget would 429 every other client
     * of the same Fess instance.
     * </p>
     *
     * @param request the servlet request
     * @param context the call context
     * @return the non-null key to rate-limit on
     */
    protected String resolveRateLimitKey(final HttpServletRequest request, final McpCallContext context) {
        final String subject = resolvePrincipalSubject(context);
        if (subject != null) {
            return subject;
        }
        final String clientIp = resolveClientIp(request);
        return clientIp != null ? clientIp : "unknown";
    }

    /**
     * Resolves the caller's IP, honouring proxy headers only from a trusted proxy.
     * <p>
     * Delegates to Fess's own {@code RateLimitHelper#getClientIp}, which consults
     * {@code X-Forwarded-For} / {@code X-Real-IP} <em>only</em> when {@code getRemoteAddr()} is
     * listed in {@code rate.limit.trusted.proxies} (default {@code 127.0.0.1,::1}), and returns
     * {@code getRemoteAddr()} otherwise. Reusing it rather than reading the headers here is the
     * whole point: a plugin that trusted {@code X-Forwarded-For} unconditionally would let any
     * caller mint an unlimited number of rate-limit keys just by varying a header, which is
     * strictly worse than the shared-bucket problem being fixed.
     * </p>
     * <p>
     * The residual trade-off is deliberate and worth stating. Where a trusted proxy <em>is</em>
     * configured, this takes the first {@code X-Forwarded-For} element, which a client can forge
     * if that proxy appends to the header instead of replacing it -- turning one shared bucket
     * into unlimited per-key buckets for a determined attacker. Fess's own {@code rate.limit.*}
     * filter accepts the same trade-off, and it is the better default: without it, the limiter
     * is not merely bypassable by an attacker but actively harmful to innocent clients, who all
     * share a single bucket they cannot influence.
     * </p>
     * <p>
     * Isolated behind this seam because it reads {@code ComponentUtil}, and the HTTP-boundary
     * test suite is container-free -- the same discipline {@link #getAuthMode()} and
     * {@link #getRateLimitPerMinute()} use.
     * </p>
     *
     * @param request the servlet request
     * @return the caller's IP, or {@code null} when it cannot be determined
     */
    protected String resolveClientIp(final HttpServletRequest request) {
        return ComponentUtil.getRateLimitHelper().getClientIp(request);
    }

    /**
     * Resolves the authenticated subject for {@code context}, if any.
     * <p>
     * Reads it off {@link McpCallContext#getPrincipal()} -- {@code none} mode resolves the
     * shared {@link McpPrincipal#anonymous()}, whose subject is {@code null}, so
     * {@link #resolveRateLimitKey} falls back to the caller's IP exactly as before this method
     * had anything to return; {@code fess_token} mode resolves a real, collision-safe, non-secret
     * subject (see {@code FessTokenAuthenticator#subjectFor}), so an authenticated caller is now
     * rate-limited per-token rather than per-IP -- multiple callers behind one NAT no longer
     * share a bucket, and one token used from several source IPs gets exactly one.
     * </p>
     *
     * @param context the call context
     * @return the authenticated subject, or {@code null} when the caller is unauthenticated
     */
    protected String resolvePrincipalSubject(final McpCallContext context) {
        final McpPrincipal principal = context.getPrincipal();
        return principal != null ? principal.getSubject() : null;
    }

    /**
     * Returns the shared rate limiter, building it from {@link #getRateLimitPerMinute()} on
     * first use.
     * <p>
     * Built lazily rather than eagerly in the constructor so that constructing a
     * {@code McpApiManager} -- something the container-free test suite does for every test --
     * never touches {@code ComponentUtil}; the {@code ComponentUtil} read only happens the first
     * time a {@code tools/call} or {@code completion/complete} request actually needs it. The
     * built instance is cached on the instance field so its per-key windows persist across
     * requests instead of resetting on every call.
     * </p>
     *
     * @return the rate limiter, shared across every request this manager processes
     */
    protected synchronized RateLimiter getRateLimiter() {
        if (rateLimiter == null) {
            rateLimiter = new RateLimiter(getRateLimitPerMinute());
        }
        return rateLimiter;
    }

    /**
     * Returns the configured per-key request limit.
     *
     * @return the number of {@code tools/call}/{@code completion/complete} calls a single key
     *         may make per minute; {@code 0} disables the limiter
     */
    protected int getRateLimitPerMinute() {
        return getSystemPropertyAsInt("mcp.rate.limit.per.minute", 60);
    }

    /**
     * Authenticates the caller according to {@code mcp.auth.mode}, and -- only for a mode that
     * owns role resolution -- seeds the {@code userRoles} request attribute Fess's
     * {@code RoleQueryHelper} consults.
     * <p>
     * The {@code none} mode (the default) deliberately does <em>not</em> seed that attribute.
     * {@code /mcp} already honours an {@code Authorization: Bearer <fess-token>} caller
     * implicitly today, because this endpoint's search calls use {@code SearchRequestType.JSON},
     * which makes {@code RoleQueryHelper} treat the call as an API request and consult its own
     * access-token resolution. Seeding {@code userRoles} unconditionally would short-circuit
     * that resolution via {@code RoleQueryHelper}'s own early return -- {@code userRoles} present
     * means "trust this set, do not look any further" -- silently dropping every such existing
     * deployment to guest permissions. See {@link McpAuthenticator#ownsRoleResolution()} for the
     * mode-by-mode contract that keeps this correct as new modes are added.
     * </p>
     *
     * @param request the servlet request
     * @param response the servlet response, used to attach a {@code WWW-Authenticate} challenge
     *            when authentication fails
     * @return the caller; never {@code null}, may be {@link McpPrincipal#anonymous()}
     * @throws McpError with HTTP 401 or 403 when authentication or authorization fails
     */
    protected McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
        final McpAuthenticator authenticator = getAuthenticator();
        final McpPrincipal principal = authenticator.authenticate(request, response);
        if (authenticator.ownsRoleResolution()) {
            request.setAttribute(McpConstants.USER_ROLES_ATTRIBUTE, resolveRoles(principal));
        }
        return principal;
    }

    /**
     * Selects the {@link McpAuthenticator} for the configured {@code mcp.auth.mode}.
     * <p>
     * {@code fess_token} always resolves to {@link FessTokenAuthenticator}. {@code oauth}
     * resolves to {@link OAuthResourceServerAuthenticator} only when
     * {@link OAuthResourceServerAuthenticator#isUsable()} agrees (i.e. {@code mcp.oauth.issuer},
     * {@code mcp.oauth.audience}, and {@code mcp.oauth.jwks.uri} are all set, and the audience is
     * compatible); an unusable {@code oauth} configuration falls back to {@code none} rather than
     * serving a broken protected-resource document with an empty {@code authorization_servers}.
     * Every other value -- the default {@code none}, and any empty or unrecognised string --
     * resolves to {@link NoneAuthenticator}. Falling back rather than failing closed or raising
     * an error keeps this call site stable as future modes are added: adding one means adding a
     * branch here, not reshaping {@link #authenticate}.
     * </p>
     * <p>
     * Every one of those reads happens per request, not once at startup, so the answer can and
     * does change while the server is running. {@link #noteAuthState} is what makes such a
     * change visible; see its Javadoc for why it belongs on this hot path and why it does not
     * simply log every time.
     * </p>
     *
     * @return the authenticator to use for this request
     */
    protected McpAuthenticator getAuthenticator() {
        final String authMode = getAuthMode();
        final AuthState state = resolveAuthState(authMode);
        noteAuthState(state, authMode);
        switch (state) {
        case FESS_TOKEN:
            return fessTokenAuthenticator;
        case OAUTH:
            return getOAuthAuthenticator();
        default:
            return noneAuthenticator;
        }
    }

    /**
     * Returns the {@code mcp.auth.mode=oauth} authenticator.
     * <p>
     * Isolated behind this seam -- rather than reading the {@link #oauthAuthenticator} field
     * directly from {@link #getAuthenticator()} and {@link #warnIfAuthenticationIsDisabled()} --
     * so a container-free test can substitute an {@link OAuthResourceServerAuthenticator}
     * subclass whose {@code isUsable()} is overridden directly, exercising both branches of
     * {@link #getAuthenticator()}'s {@code oauth} handling without needing a live DI container
     * (the real, non-overridden {@code isUsable()} reads {@code mcp.oauth.issuer} via
     * {@code ComponentUtil}).
     * </p>
     *
     * @return the oauth-mode authenticator, shared across every request this manager processes
     */
    protected OAuthResourceServerAuthenticator getOAuthAuthenticator() {
        return oauthAuthenticator;
    }

    /**
     * Returns the configured authentication mode.
     * <p>
     * Delegates to {@link #getSystemProperty(String, String)} rather than calling
     * {@code ComponentUtil.getFessConfig().getSystemProperty(...)} directly, so a container-free
     * test can stub that one primitive and drive this method's real body -- including its
     * literal {@code "mcp.auth.mode"} key and {@link #AUTH_MODE_NONE} default argument -- without
     * needing a live DI container. Every test double in this suite otherwise overrides
     * {@code getAuthMode()} itself, which would leave this method's own body permanently
     * unexercised.
     * </p>
     *
     * @return {@code mcp.auth.mode}'s value; {@link #AUTH_MODE_NONE} when unset
     */
    protected String getAuthMode() {
        return getSystemProperty("mcp.auth.mode", AUTH_MODE_NONE);
    }

    /**
     * Reads a String-valued Fess system property.
     * <p>
     * Isolated so {@link #getAuthMode()} itself can be exercised container-free: this is the
     * only place in that call chain that touches {@code ComponentUtil}. {@link #getAllowedOrigins()}
     * is the one remaining consumer of a different-typed system property that is not routed
     * through a property-level seam ({@link #getSystemPropertyAsBoolean(String, boolean)} and
     * {@link #getSystemPropertyAsInt(String, int)} cover the rest -- see those seams' own
     * Javadoc); it already has its own established, reviewed container-free test double (each
     * test file overrides {@code getAllowedOrigins()} directly), and its parsing (comma-split,
     * trim, filter blanks) is more than a bare default-value passthrough, so routing it through
     * {@link #getSystemProperty(String, String)} here would not exercise anything {@code
     * getAllowedOrigins()}'s own test does not already cover.
     * </p>
     *
     * @param key the system property key
     * @param defaultValue the value to return when the property is unset
     * @return the property's value, or {@code defaultValue} when unset
     */
    protected String getSystemProperty(final String key, final String defaultValue) {
        return ComponentUtil.getFessConfig().getSystemProperty(key, defaultValue);
    }

    /**
     * Reads a boolean-valued Fess system property.
     * <p>
     * Isolated the same way {@link #getSystemProperty(String, String)} is for {@link
     * #getAuthMode()}: {@link #isEnabled()}'s real (non-overridden) body is otherwise never
     * exercised by this suite (every test double overrides {@code isEnabled()} wholesale), so
     * neither its literal {@code "mcp.enabled"} key nor its {@code true} default is ever actually
     * executed -- a typo in the key, or a flipped default, would silently disable the endpoint
     * everywhere and pass every test.
     * </p>
     *
     * @param key the system property key
     * @param defaultValue the value to return when the property is unset
     * @return the property's value, or {@code defaultValue} when unset
     */
    protected boolean getSystemPropertyAsBoolean(final String key, final boolean defaultValue) {
        return ComponentUtil.getFessConfig().getSystemPropertyAsBoolean(key, defaultValue);
    }

    /**
     * Reads an int-valued Fess system property.
     * <p>
     * Isolated the same way {@link #getSystemProperty(String, String)} is for {@link
     * #getAuthMode()}, closing the same gap for {@link #getRequestMaxBytes()} and {@link
     * #getRateLimitPerMinute()}: each test double in this suite otherwise overrides the
     * higher-level method directly, so this is the only seam that lets either method's real body
     * -- including its literal key and default-value argument -- actually run container-free.
     * </p>
     *
     * @param key the system property key
     * @param defaultValue the value to return when the property is unset
     * @return the property's value, or {@code defaultValue} when unset
     */
    protected int getSystemPropertyAsInt(final String key, final int defaultValue) {
        return ComponentUtil.getFessConfig().getSystemPropertyAsInt(key, defaultValue);
    }

    /**
     * Maps a principal to the encoded Fess permissions used for search filtering.
     * <p>
     * Only called for an authenticator that {@linkplain McpAuthenticator#ownsRoleResolution()
     * owns role resolution} -- {@link #authenticate} skips it entirely for {@code none} mode, so
     * neither {@link #getSearchGuestRoleList()} nor {@link #getSearchDefaultPermissionList()}
     * (both backed by {@code ComponentUtil}) is ever consulted on that path.
     * </p>
     * <p>
     * Seeding {@code userRoles} makes {@code RoleQueryHelper.build} return via its early return,
     * which skips <em>every</em> other role source it would otherwise consult: the request
     * parameter/header/cookie role channels, and the permissions of a logged-in
     * {@code FessUserBean}. None of those apply to an MCP caller -- this endpoint has no session
     * and configures none of those channels -- so skipping them is a correct no-op, not a gap.
     * The two effects this method exists to reproduce are the ones that are <em>not</em> no-ops
     * for MCP: the unconditional {@code role.search.default.permissions} addition, and the
     * guest-role fallback for a caller with no resolved permissions of their own.
     * </p>
     *
     * @param principal the caller
     * @return the permission set, falling back to the configured guest roles when
     *         {@code principal} carries none of its own
     */
    protected Set<String> resolveRoles(final McpPrincipal principal) {
        final Set<String> roles = new HashSet<>(principal.getPermissions());
        if (roles.isEmpty()) {
            // getSearchGuestRoleList also appends the "1guest" user form; splitting the
            // property by hand would lose it.
            roles.addAll(getSearchGuestRoleList());
        }
        // RoleQueryHelper's early return skips role.search.default.permissions, so add it here.
        roles.addAll(getSearchDefaultPermissionList());
        return roles;
    }

    /**
     * Returns the configured guest role list, including the {@code "1guest"} user form.
     *
     * @return {@code FessConfig#getSearchGuestRoleList()}'s result
     */
    protected List<String> getSearchGuestRoleList() {
        return ComponentUtil.getFessConfig().getSearchGuestRoleList();
    }

    /**
     * Returns the encoded {@code role.search.default.permissions} list.
     *
     * @return {@code FessConfig#getSearchDefaultPermissionsAsArray()}'s result, as a list
     */
    protected List<String> getSearchDefaultPermissionList() {
        return Arrays.asList(ComponentUtil.getFessConfig().getSearchDefaultPermissionsAsArray());
    }

    /**
     * Returns whether the MCP endpoint is enabled.
     *
     * @return true when mcp.enabled is "true"
     */
    protected boolean isEnabled() {
        // getSystemPropertyAsBoolean treats anything other than "true" as false.
        return getSystemPropertyAsBoolean("mcp.enabled", true);
    }

    /**
     * Returns the maximum accepted request body size in bytes.
     *
     * @return the limit in bytes
     */
    protected int getRequestMaxBytes() {
        return getSystemPropertyAsInt("mcp.request.max.bytes", 1048576);
    }

    /**
     * Returns the dispatcher that routes calls to their per-method handler.
     *
     * @return the dispatcher
     */
    protected McpDispatcher getDispatcher() {
        return dispatcher;
    }

    /**
     * Returns the writer that stamps this server's identity onto every response.
     *
     * @return the response writer
     */
    protected McpResponseWriter getResponseWriter() {
        return responseWriter;
    }

    @Override
    protected void writeHeaders(final HttpServletResponse response) {
        ComponentUtil.getFessConfig().getApiJsonResponseHeaderList().forEach(e -> {
            // CorsFilter already emitted Vary: Origin; setHeader would replace it.
            if ("Vary".equalsIgnoreCase(e.getFirst())) {
                response.addHeader(e.getFirst(), e.getSecond());
            } else {
                response.setHeader(e.getFirst(), e.getSecond());
            }
        });
    }
}
