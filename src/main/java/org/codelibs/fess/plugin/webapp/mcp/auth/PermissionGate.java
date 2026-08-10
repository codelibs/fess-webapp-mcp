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

import java.util.Set;

/**
 * The single "may this caller use a gated primitive" decision, shared by every handler that
 * needs to enforce {@link org.codelibs.fess.plugin.webapp.mcp.tool.McpTool#getRequiredPermissions()}.
 * <p>
 * {@code ToolsListHandler}, {@code ToolsCallHandler}, {@code ResourcesListHandler}, and
 * {@code ResourcesReadHandler} each gate {@code get_index_stats} / {@code fess://index/stats}
 * against the caller's {@link McpPrincipal}. Those four classes share no common superclass --
 * {@code ToolsCallHandler} is deliberately not a {@code CacheableResult} handler and must not
 * extend {@code AbstractCacheableHandler} -- so the check lives here, as a pure static method
 * every one of them calls, rather than as a {@code protected} method copy-pasted four times or
 * forced onto an artificial shared base.
 * </p>
 * <p>
 * The shape mirrors Fess's own {@code FessProp#isApiAdminAccessAllowed}: allowed when the
 * caller's permission set and the primitive's required set intersect. This method touches
 * nothing beyond its two arguments -- no {@code ComponentUtil}, no I/O -- so calling it costs a
 * container-free test nothing, and a primitive with an empty required set is allowed without
 * ever inspecting {@code principal}.
 * </p>
 */
public final class PermissionGate {

    private PermissionGate() {
        // static utility only
    }

    /**
     * Returns whether the caller may use a primitive with the given requirement.
     *
     * @param required the encoded Fess permissions the primitive requires; an empty set means
     *            the primitive is not gated and every caller is allowed
     * @param principal the caller, or {@code null} when no authenticator resolved one
     * @return {@code true} when {@code required} is empty, or when {@code principal} is not
     *         {@code null} and its {@link McpPrincipal#getPermissions()} contains at least one
     *         permission in {@code required}; {@code false} otherwise
     */
    public static boolean isAllowed(final Set<String> required, final McpPrincipal principal) {
        if (required.isEmpty()) {
            return true;
        }
        if (principal == null) {
            return false;
        }
        return principal.getPermissions().stream().anyMatch(required::contains);
    }
}
