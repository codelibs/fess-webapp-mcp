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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@code mcp.auth.mode=none} strategy: authenticates nobody and rejects nobody.
 * <p>
 * This is the default mode, chosen so an existing Fess deployment keeps working unmodified
 * after upgrading this plugin. It never touches {@code ComponentUtil} or any other part of the
 * DI container -- {@link #authenticate} is a pure function of its (ignored) arguments -- so
 * routing a request through this authenticator costs nothing beyond the allocation of this
 * class's single shared {@link McpPrincipal#anonymous()} return value.
 * </p>
 * <p>
 * Critically, {@link #ownsRoleResolution()} returns {@code false}. {@code /mcp} already honours
 * an {@code Authorization: Bearer <fess-token>} caller implicitly, because Fess's
 * {@code RoleQueryHelper} treats every MCP call as an API request and consults its own
 * access-token resolution. If this class instead told the caller to seed the {@code userRoles}
 * request attribute, that would short-circuit {@code RoleQueryHelper} before its access-token
 * step ever ran, silently dropping every such deployment to guest permissions. Returning
 * {@code false} leaves that resolution alone.
 * </p>
 */
public final class NoneAuthenticator implements McpAuthenticator {

    /**
     * Creates a {@code none}-mode authenticator. Stateless: every instance behaves identically.
     */
    public NoneAuthenticator() {
        // no state
    }

    @Override
    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
        return McpPrincipal.anonymous();
    }

    @Override
    public boolean ownsRoleResolution() {
        return false;
    }
}
