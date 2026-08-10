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
package org.codelibs.fess.plugin.webapp.mcp.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;

/**
 * Validates the MCP request-metadata headers the Streamable HTTP transport requires for
 * protocol revision 2026-07-28, and checks them against the request body.
 *
 * <p>
 * The transport mandates that every request POST (notifications excepted) carries
 * {@code MCP-Protocol-Version} and {@code Mcp-Method} headers, plus {@code Mcp-Name} for
 * the methods whose body carries a name-like identifier. Each header must duplicate the
 * corresponding body value exactly; disagreement, like absence, is a {@code -32020}
 * {@code HeaderMismatch} answered with HTTP 400. This class does not decide whether a
 * request is a notification -- callers must not invoke either entry point for one, since
 * the transport leaves header requirements for notification POSTs undefined.
 * </p>
 */
public final class HeaderValidator {

    /** Marks the start of the non-ASCII header-value sentinel, e.g. {@code =?base64?<b64utf8>?=}. */
    private static final String SENTINEL_PREFIX = "=?base64?";

    /** Marks the end of the non-ASCII header-value sentinel. */
    private static final String SENTINEL_SUFFIX = "?=";

    /** The {@code params} field name {@code Mcp-Name} mirrors for {@code tools/call} and {@code prompts/get}. */
    private static final String PARAM_NAME = "name";

    /** The {@code params} field name {@code Mcp-Name} mirrors for {@code resources/read}. */
    private static final String PARAM_URI = "uri";

    /** Methods whose {@code Mcp-Name} header mirrors {@code params.name}. */
    private static final Set<String> NAME_METHODS = Set.of("tools/call", "prompts/get");

    /** Methods whose {@code Mcp-Name} header mirrors {@code params.uri}. */
    private static final Set<String> URI_METHODS = Set.of("resources/read");

    private HeaderValidator() {
        // no instantiation
    }

    /**
     * Verifies that every header this method requires is present, without checking whether
     * its value agrees with the body.
     *
     * <p>
     * Call this before {@link #requireMatches}: the transport lists a missing required
     * header as a {@code -32020} condition, so validating the protocol version first would
     * answer a header-less request with {@code -32022} ({@code UnsupportedProtocolVersion})
     * instead.
     * </p>
     *
     * @param request the servlet request carrying the candidate headers
     * @param mcpRequest the parsed envelope, used only to decide whether {@code Mcp-Name} is
     *            required for this method
     * @throws McpError with HTTP 400 and {@link ErrorCode#HeaderMismatch} when a header this
     *             method requires is missing
     */
    public static void requirePresent(final HttpServletRequest request, final McpRequest mcpRequest) {
        require(request, McpConstants.HEADER_PROTOCOL_VERSION);
        require(request, McpConstants.HEADER_METHOD);
        if (needsName(mcpRequest.getMethod())) {
            require(request, McpConstants.HEADER_NAME);
        }
    }

    /**
     * Verifies that each header present agrees with the request body.
     *
     * <p>
     * Header names are matched case-insensitively by the servlet API; header values are
     * compared case-sensitively against the body, after decoding the {@code =?base64?...?=}
     * sentinel. Callers are expected to have already run {@link #requirePresent}, so a
     * missing header surfaces here as a mismatch against a non-null body value; a method
     * that does not mirror {@code Mcp-Name} is not checked against it, even if the header
     * happens to be present.
     * </p>
     *
     * @param request the servlet request carrying the candidate headers
     * @param mcpRequest the parsed envelope
     * @param meta the parsed {@code params._meta}, supplying the protocol version to compare
     * @throws McpError with HTTP 400 and {@link ErrorCode#HeaderMismatch} when a checked
     *             header disagrees with the body
     */
    public static void requireMatches(final HttpServletRequest request, final McpRequest mcpRequest, final McpRequestMeta meta) {
        match(McpConstants.HEADER_PROTOCOL_VERSION, request.getHeader(McpConstants.HEADER_PROTOCOL_VERSION), meta.getProtocolVersion());
        match(McpConstants.HEADER_METHOD, request.getHeader(McpConstants.HEADER_METHOD), mcpRequest.getMethod());
        final String method = mcpRequest.getMethod();
        if (needsName(method)) {
            final String bodyField = URI_METHODS.contains(method) ? PARAM_URI : PARAM_NAME;
            final Object bodyValue = mcpRequest.getParams().get(bodyField);
            match(McpConstants.HEADER_NAME, decodeSentinel(request.getHeader(McpConstants.HEADER_NAME)),
                    bodyValue == null ? null : bodyValue.toString());
        }
    }

    /**
     * Decodes the {@code =?base64?<b64utf8>?=} sentinel MCP 2026-07-28 uses to carry
     * non-ASCII header values, which HTTP header fields cannot transport directly.
     *
     * @param value the raw header value; may be null when the header was absent
     * @return the decoded value when {@code value} is a well-formed sentinel; {@code value}
     *         unchanged when it is null or is not a sentinel
     * @throws McpError with HTTP 400 and {@link ErrorCode#HeaderMismatch} when {@code value}
     *             has the sentinel's prefix and suffix but its payload is not valid base64
     */
    public static String decodeSentinel(final String value) {
        if (value == null || !value.startsWith(SENTINEL_PREFIX) || !value.endsWith(SENTINEL_SUFFIX)) {
            return value;
        }
        final String encoded = value.substring(SENTINEL_PREFIX.length(), value.length() - SENTINEL_SUFFIX.length());
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.HeaderMismatch,
                    McpConstants.HEADER_NAME + " carries a malformed base64 sentinel");
        }
    }

    /**
     * Decides whether {@code method} requires the {@code Mcp-Name} header, and which
     * {@code params} field it must mirror.
     *
     * @param method the JSON-RPC method name
     * @return true when {@code method} is {@code tools/call}, {@code prompts/get}, or
     *         {@code resources/read}
     */
    private static boolean needsName(final String method) {
        return NAME_METHODS.contains(method) || URI_METHODS.contains(method);
    }

    /**
     * Fails with a {@code -32020} error when {@code header} is absent from {@code request}.
     *
     * @param request the servlet request to check
     * @param header the header name to require
     * @throws McpError with HTTP 400 and {@link ErrorCode#HeaderMismatch} when the header is
     *             missing
     */
    private static void require(final HttpServletRequest request, final String header) {
        if (request.getHeader(header) == null) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.HeaderMismatch, header + " is required");
        }
    }

    /**
     * Fails with a {@code -32020} error when the decoded header value does not equal the
     * body value.
     *
     * @param header the header name, used only for the error message
     * @param headerValue the header value already decoded from the sentinel, if any
     * @param bodyValue the value from the request body to compare against
     * @throws McpError with HTTP 400 and {@link ErrorCode#HeaderMismatch} when the values
     *             disagree, including when {@code headerValue} is null
     */
    private static void match(final String header, final String headerValue, final String bodyValue) {
        if (headerValue == null || !headerValue.equals(bodyValue)) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.HeaderMismatch, header + " does not match the request body");
        }
    }
}
