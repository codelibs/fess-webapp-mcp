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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class McpConstantsTest {

    @Test
    public void testProtocolVersion() {
        assertEquals("2026-07-28", McpConstants.PROTOCOL_VERSION);
        assertEquals(1, McpConstants.SUPPORTED_PROTOCOL_VERSIONS.size(), "modern-only server");
        assertTrue(McpConstants.SUPPORTED_PROTOCOL_VERSIONS.contains("2026-07-28"));
    }

    @Test
    public void testMetaKeys() {
        assertEquals("io.modelcontextprotocol/protocolVersion", McpConstants.META_PROTOCOL_VERSION);
        assertEquals("io.modelcontextprotocol/clientCapabilities", McpConstants.META_CLIENT_CAPABILITIES);
        assertEquals("io.modelcontextprotocol/serverInfo", McpConstants.META_SERVER_INFO);
    }
}
