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
     * Creates a new MCP API manager with the default path prefix "/mcp".
     */
    public McpApiManager() {
        // JSON-RPC endpoint is /mcp/*
        setPathPrefix("/mcp");
    }

    /**
     * Registers this API manager with the WebApiManagerFactory.
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
            authenticate(request, response); // Task 13-14

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

            final McpCallContext context = new McpCallContext(mcpRequest, meta, mcpRequest.getParams());
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
     * Always returns {@code null} today: {@link McpCallContext} does not yet carry a resolved
     * principal, since authentication is Tasks 13-14's work, not this one's. Isolating the
     * lookup in its own seam means those tasks only need to change this one method -- to read
     * the subject off the principal {@link McpCallContext} will then carry -- without touching
     * {@link #resolveRateLimitKey} or {@link #enforceRateLimit} at all.
     * </p>
     *
     * @param context the call context
     * @return the authenticated subject, or {@code null} when the caller is unauthenticated (or,
     *         as today, when this server does not yet resolve one at all)
     */
    protected String resolvePrincipalSubject(final McpCallContext context) {
        return null;
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
        return ComponentUtil.getFessConfig().getSystemPropertyAsInt("mcp.rate.limit.per.minute", 60);
    }

    /**
     * Authenticates and authorizes the caller.
     * <p>
     * No-op placeholder. Tasks 13-14 implement authentication and authorization (HTTP 401/403
     * with a {@code WWW-Authenticate} challenge); this seam exists now so {@link #process} calls
     * it at the pipeline position the design mandates, ahead of those tasks landing.
     * {@link McpCallContext} does not yet carry a resolved principal -- adding one is those
     * tasks' work too.
     * </p>
     *
     * @param request the servlet request
     * @param response the servlet response, needed once a failure must set
     *            {@code WWW-Authenticate}
     */
    protected void authenticate(final HttpServletRequest request, final HttpServletResponse response) {
        // Tasks 13-14 fill this in.
    }

    /**
     * Returns whether the MCP endpoint is enabled.
     *
     * @return true when mcp.enabled is "true"
     */
    protected boolean isEnabled() {
        // getSystemPropertyAsBoolean treats anything other than "true" as false.
        return ComponentUtil.getFessConfig().getSystemPropertyAsBoolean("mcp.enabled", true);
    }

    /**
     * Returns the maximum accepted request body size in bytes.
     *
     * @return the limit in bytes
     */
    protected int getRequestMaxBytes() {
        return ComponentUtil.getFessConfig().getSystemPropertyAsInt("mcp.request.max.bytes", 1048576);
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
