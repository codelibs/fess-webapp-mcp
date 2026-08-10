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
package org.codelibs.fess.plugin.webapp.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class McpToolTest {

    private final List<McpTool> tools = List.of(new SearchTool(), new SuggestTool(), new GetDocumentTool(), new IndexStatsTool());

    @Test
    public void testNamesAreUniqueAndSpecCompliant() {
        assertEquals(4, tools.stream().map(McpTool::getName).distinct().count(), "tool names must be unique");
        for (final McpTool tool : tools) {
            assertTrue(tool.getName().matches("[A-Za-z0-9_.-]{1,128}"), "illegal tool name: " + tool.getName());
            assertNotNull(tool.getDescription(), tool.getName());
            assertEquals("object", tool.getInputSchema().get("type"), tool.getName());
        }
    }

    @Test
    public void testEveryToolDeclaresAnOutputSchema() {
        for (final McpTool tool : tools) {
            assertNotNull(tool.getOutputSchema(), tool.getName() + " must declare an outputSchema");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSearchSchemaMatchesTheImplementation() {
        final Map<String, Object> properties = (Map<String, Object>) new SearchTool().getInputSchema().get("properties");
        assertFalse(properties.containsKey("preference"), "preference was declared but never read");
        assertTrue(properties.containsKey("as"), "as is read at McpApiManager:686 and must be declared");
        assertTrue(properties.containsKey("ex_q"), "ex_q is read at McpApiManager:780");
        assertTrue(properties.containsKey("sdh"), "sdh is read at McpApiManager:801");
        assertFalse(((Map<String, Object>) properties.get("num")).containsKey("default"),
                "the default lives in mcp.default.page.size, not in the schema");
    }

    @Test
    public void testIndexStatsIsPermissionGated() {
        // getIndexStatsPermissions() reads Fess config, which needs a DI container this
        // container-free suite does not provide; override the seam instead of the container.
        final IndexStatsTool gated = new IndexStatsTool() {
            @Override
            protected String getIndexStatsPermissions() {
                return "Radmin-api";
            }
        };
        assertFalse(gated.getRequiredPermissions().isEmpty(), "get_index_stats leaks the index name, document count and JVM heap");
        assertTrue(new SearchTool().getRequiredPermissions().isEmpty(), "search is gated by Fess role filtering, not by this");
    }
}
