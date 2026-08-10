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
            checkRateLimit(request); // Task 11
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
     * Enforces the per-client request rate limit.
     * <p>
     * No-op placeholder. Task 11 implements rate limiting (HTTP 429 + {@code Retry-After} when
     * exceeded); this seam exists now so {@link #process} calls it at the pipeline position the
     * design mandates, ahead of that task landing.
     * </p>
     *
     * @param request the servlet request
     */
    protected void checkRateLimit(final HttpServletRequest request) {
        // Task 11 fills this in.
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
