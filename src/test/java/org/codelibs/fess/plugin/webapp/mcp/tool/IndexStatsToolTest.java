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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link IndexStatsTool}.
 *
 * <p>
 * {@code testCollectIndexStats_RequiresDIContainer} and
 * {@code testInvokeGetIndexStats_RequiresDIContainer} migrated from {@code McpApiManagerTest}
 * when {@code collectIndexStats} and {@code invokeGetIndexStats} moved out of
 * {@code McpApiManager} into this class. {@code testGetNameAndDescription} and
 * {@code testAnnotationsAreReadOnlyAndNotDestructive} migrated similarly from
 * {@code testHandleListTools_GetIndexStatsTool}/{@code testHandleListTools_IndexStatsToolAnnotations}.
 * </p>
 */
public class IndexStatsToolTest {

    private final IndexStatsTool indexStatsTool = new IndexStatsTool();

    @Test
    public void testGetNameAndDescription() {
        assertEquals("get_index_stats", indexStatsTool.getName());
        assertEquals("Get index statistics and information", indexStatsTool.getDescription());
    }

    @Test
    public void testAnnotationsAreReadOnlyAndNotDestructive() {
        final Map<String, Object> annotations = indexStatsTool.getAnnotations();
        assertEquals(true, annotations.get("readOnlyHint"), "Stats should be read-only");
        assertEquals(false, annotations.get("destructiveHint"), "Stats should not be destructive");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInputSchemaPropertiesIsEmpty() {
        final Map<String, Object> schema = indexStatsTool.getInputSchema();
        assertEquals("object", schema.get("type"), "Stats schema type should be object");
        final Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.isEmpty(), "get_index_stats takes no arguments");
    }

    @Test
    public void testCollectIndexStats_RequiresDIContainer() {
        // collectIndexStats requires ComponentUtil which needs DI container
        try {
            indexStatsTool.collectIndexStats();
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testCall_RequiresDIContainer() {
        // call() requires ComponentUtil which needs DI container
        try {
            indexStatsTool.call(Map.of(), new McpCallContext());
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testGetIndexStatsPermissions_RequiresDIContainer() {
        // The seam propagates a container-not-available failure rather than masking it: this
        // tool only ever runs inside the Fess webapp, where the container is always up, so a
        // container failure here would indicate a real bug, not an operating condition to hide.
        try {
            indexStatsTool.getIndexStatsPermissions();
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testGetRequiredPermissions_RequiresDIContainer() {
        // getRequiredPermissions() delegates straight to getIndexStatsPermissions() with no
        // catch of its own, so the same container failure surfaces here too.
        try {
            indexStatsTool.getRequiredPermissions();
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testGetRequiredPermissions_CommaSeparatedOverride() {
        final IndexStatsTool tool = new IndexStatsTool() {
            @Override
            protected String getIndexStatsPermissions() {
                return "Radmin-api, Aadmin-api ,Sadmin-api";
            }
        };
        assertEquals(Set.of("Radmin-api", "Aadmin-api", "Sadmin-api"), tool.getRequiredPermissions());
    }

    @Test
    public void testGetRequiredPermissions_BlankSettingDisablesGate() {
        final IndexStatsTool tool = new IndexStatsTool() {
            @Override
            protected String getIndexStatsPermissions() {
                return "  ";
            }
        };
        assertTrue(tool.getRequiredPermissions().isEmpty(), "A blank setting must disable the gate");
    }
}
