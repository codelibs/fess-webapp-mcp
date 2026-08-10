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
     * Emits a one-time startup WARN when {@code mcp.auth.mode} resolves to the default,
     * unauthenticated {@code none} mode -- and, when {@code mcp.auth.mode=oauth} but
     * {@link OAuthResourceServerAuthenticator#isUsable()} is {@code false}, a one-time startup
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
     */
    protected void warnIfAuthenticationIsDisabled() {
        final String authMode = getAuthMode();
        final boolean oauthRequestedButUnusable = AUTH_MODE_OAUTH.equals(authMode) && !getOAuthAuthenticator().isUsable();
        if (oauthRequestedButUnusable && logger.isErrorEnabled()) {
            logger.error("[MCP] mcp.auth.mode=oauth but the configuration is not usable - falling back to none. "
                    + "mcp.oauth.issuer must be set to the authorization server's issuer URL; mcp.oauth.audience must be "
                    + "set (it is REQUIRED for oauth mode -- it is never derived from the request's Host header) and must "
                    + "end in /mcp (the only resource path this server's metadata endpoint serves); and mcp.oauth.jwks.uri "
                    + "must be set to the authorization server's JWKS endpoint.");
        }
        final boolean authenticated =
                AUTH_MODE_FESS_TOKEN.equals(authMode) || (AUTH_MODE_OAUTH.equals(authMode) && !oauthRequestedButUnusable);
        if (!authenticated && logger.isWarnEnabled()) {
            logger.warn(
                    "[MCP] mcp.auth.mode={} - every /mcp caller is treated as anonymous. "
                            + "Set mcp.auth.mode=fess_token, or mcp.auth.mode=oauth with mcp.oauth.issuer set, to require a credential.",
                    authMode);
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

            final String body = readBoundedRequestBody(request);
            final McpRequest mcpRequest = McpRequest.parse(Json.parseObject(body));
            hasId = mcpRequest.hasId();
            id = mcpRequest.getId();

            // Notifications carry no _meta and no metadata headers. This check MUST precede
            // both, or every conformant notification would be rejected with a 400.
            if (mcpRequest.isNotification()) {
                writer.writeAccepted(response);
                return;
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
     * Reads the raw request body from the HTTP request.
     *
     * @param request the HTTP servlet request
     * @return the request body as a string
     * @throws IOException if an I/O error occurs while reading the request
     */
    protected String readRequestBody(final HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), Constants.UTF_8);
    }

    /**
     * Reads the request body, refusing anything over the configured limit.
     *
     * @param request the servlet request
     * @return the body text
     * @throws IOException if reading fails
     * @throws McpError with HTTP 413 when the body is too large
     */
    protected String readBoundedRequestBody(final HttpServletRequest request) throws IOException {
        final String body = readRequestBody(request);
        final int max = getRequestMaxBytes();
        if (body.getBytes(Constants.UTF_8).length > max) {
            throw new McpError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.InvalidRequest,
                    "request body exceeds mcp.request.max.bytes (" + max + ")");
        }
        return body;
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
     * @return the allowed origins; empty means same-origin only
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
     * {@link McpDispatcher#dispatch}, not earlier in {@link #process}. Only {@code tools/call}
     * and {@code completion/complete} are limited, per the spec's server MUST; {@code
     * server/discover} and the list methods are metadata reads and are deliberately excluded.
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
        if (!"tools/call".equals(method) && !"completion/complete".equals(method)) {
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
     * Keys on the authenticated subject when {@link #resolvePrincipalSubject} resolves one,
     * the caller's IP address otherwise. {@code request.getRemoteAddr()} returning {@code null}
     * -- not expected from a real container, but possible from a test double -- falls back to
     * the literal {@code "unknown"} rather than a {@code null} key, since {@code
     * ConcurrentHashMap} (which {@link RateLimiter} is built on) rejects {@code null} keys
     * outright.
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
        final String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
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
     * {@link OAuthResourceServerAuthenticator#isUsable()} agrees (i.e. {@code mcp.oauth.issuer}
     * is set); an unusable {@code oauth} configuration falls back to {@code none} rather than
     * serving a broken protected-resource document with an empty {@code authorization_servers}.
     * Every other value -- the default {@code none}, and any empty or unrecognised string --
     * resolves to {@link NoneAuthenticator}. Falling back rather than failing closed or raising
     * an error keeps this call site stable as future modes are added: adding one means adding a
     * branch here, not reshaping {@link #authenticate}.
     * </p>
     *
     * @return the authenticator to use for this request
     */
    protected McpAuthenticator getAuthenticator() {
        final String authMode = getAuthMode();
        if (AUTH_MODE_FESS_TOKEN.equals(authMode)) {
            return fessTokenAuthenticator;
        }
        if (AUTH_MODE_OAUTH.equals(authMode)) {
            final OAuthResourceServerAuthenticator oauth = getOAuthAuthenticator();
            if (oauth.isUsable()) {
                return oauth;
            }
        }
        return noneAuthenticator;
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
