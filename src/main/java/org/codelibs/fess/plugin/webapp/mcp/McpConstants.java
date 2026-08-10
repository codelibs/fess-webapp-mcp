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

import java.util.Set;

/**
 * Wire-level constants for MCP protocol revision 2026-07-28.
 */
public final class McpConstants {

    /** The single MCP protocol revision this server implements. */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    /** Every protocol revision this server accepts. Modern-only: no legacy handshake. */
    public static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(PROTOCOL_VERSION);

    /** JSON-RPC version string. */
    public static final String JSONRPC_VERSION = "2.0";

    /** HTTP header carrying the protocol version; must match the value in {@code params._meta}. */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    /** HTTP header carrying the JSON-RPC method; must match the body's {@code method}. */
    public static final String HEADER_METHOD = "Mcp-Method";

    /** HTTP header carrying {@code params.name} or {@code params.uri}. */
    public static final String HEADER_NAME = "Mcp-Name";

    /** Required {@code _meta} key holding the client's protocol version. */
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    /** Required {@code _meta} key holding the client's declared capabilities. */
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    /** Optional {@code _meta} key holding the client's identity. */
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    /** {@code _meta} key this server sets on every result. */
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    /**
     * Request attribute consulted by Fess's RoleQueryHelper. Setting it short-circuits
     * role resolution, so it must only be set when this plugin owns authentication.
     * The name mirrors the protected constant RoleQueryHelper.USER_ROLES, which is not
     * visible from plugin code.
     */
    public static final String USER_ROLES_ATTRIBUTE = "userRoles";

    /** Result discriminator for a completed call. */
    public static final String RESULT_TYPE_COMPLETE = "complete";

    private McpConstants() {
        // no instantiation
    }
}
