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

import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Resolves the caller of an MCP request under a single {@code mcp.auth.mode}.
 * <p>
 * {@code McpApiManager#authenticate} selects one implementation per {@code mcp.auth.mode}
 * value and delegates to it for every request. Each mode is a self-contained strategy: {@code
 * none} (the default) never rejects a request and never authenticates anyone; {@code
 * fess_token} requires a valid Fess access token; a future {@code oauth} mode (not built by
 * this task) will validate a JWT bearer token against a configured issuer. Adding a mode means
 * adding an implementation and a branch in {@code McpApiManager#getAuthenticator} -- the call
 * site in {@code McpApiManager#authenticate} does not change.
 * </p>
 */
public interface McpAuthenticator {

    /**
     * Authenticates the caller.
     *
     * @param request the servlet request
     * @param response the servlet response; implementations that reject the request set a
     *            {@code WWW-Authenticate} header on it before throwing, since {@code
     *            McpError} alone carries no HTTP headers
     * @return the caller, never {@code null}; may be {@link McpPrincipal#anonymous()}
     * @throws McpError with HTTP 401 (plus a {@code WWW-Authenticate} header set on
     *             {@code response}) when the caller supplied no credential or an invalid one,
     *             or with HTTP 403 when a valid credential lacks a required scope or permission
     */
    McpPrincipal authenticate(HttpServletRequest request, HttpServletResponse response);

    /**
     * Returns whether this mode owns role resolution and must seed the {@code userRoles}
     * request attribute Fess's {@code RoleQueryHelper} consults.
     * <p>
     * {@code false} (as {@code NoneAuthenticator} returns) leaves {@code RoleQueryHelper} to
     * resolve roles on its own, exactly as it already does today -- including honouring a
     * legacy {@code Authorization: Bearer <fess-token>} caller via its own {@code
     * processAccessToken} step. {@code true} (as {@code FessTokenAuthenticator} returns) tells
     * {@code McpApiManager#authenticate} to set the attribute itself from this authenticator's
     * resolved {@link McpPrincipal#getPermissions()}, which short-circuits {@code
     * RoleQueryHelper} entirely -- correct only when this authenticator has already done that
     * resolution itself.
     * </p>
     *
     * @return {@code true} when {@code McpApiManager#authenticate} must seed {@code userRoles}
     *         with this authenticator's resolved permissions; {@code false} to leave role
     *         resolution to {@code RoleQueryHelper}
     */
    boolean ownsRoleResolution();
}
