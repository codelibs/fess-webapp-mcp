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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link SuggestTool}.
 *
 * <p>
 * Migrated from {@code McpApiManagerTest} ({@code testResolveSuggestSize*}) when
 * {@code invokeSuggest} and {@code resolveSuggestSize} moved out of {@code McpApiManager} into
 * this class. {@code testInputSchemaRequiresQ} migrated similarly from
 * {@code testHandleListTools_HasSuggestTool}.
 * </p>
 */
public class SuggestToolTest {

    private final SuggestTool suggestTool = new SuggestTool();

    @Test
    @SuppressWarnings("unchecked")
    public void testInputSchemaRequiresQ() {
        final Map<String, Object> schema = suggestTool.getInputSchema();
        final List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("q"), "q should be required");
    }

    @Test
    public void testResolveSuggestSize_NullUsesDefault() {
        assertEquals(10, suggestTool.resolveSuggestSize(null, 100), "null num should default to 10");
    }

    @Test
    public void testResolveSuggestSize_Zero_UsesDefault() {
        assertEquals(10, suggestTool.resolveSuggestSize(Integer.valueOf(0), 100), "num=0 should fall back to default 10");
    }

    @Test
    public void testResolveSuggestSize_Negative_UsesDefault() {
        assertEquals(10, suggestTool.resolveSuggestSize(Integer.valueOf(-5), 100), "negative num should fall back to default 10");
    }

    @Test
    public void testResolveSuggestSize_OverMax_CappedAtMax() {
        assertEquals(100, suggestTool.resolveSuggestSize(Integer.valueOf(500), 100), "num exceeding max must be capped");
    }

    @Test
    public void testResolveSuggestSize_AtMax_Preserved() {
        assertEquals(100, suggestTool.resolveSuggestSize(Integer.valueOf(100), 100), "num equal to max must be preserved");
    }

    @Test
    public void testResolveSuggestSize_WithinRange_Preserved() {
        assertEquals(25, suggestTool.resolveSuggestSize(Integer.valueOf(25), 100), "num within range must be preserved");
    }

    @Test
    public void testResolveSuggestSize_StringNumericInput_Parsed() {
        assertEquals(7, suggestTool.resolveSuggestSize("7", 100), "numeric String must be parsed");
    }

    @Test
    public void testResolveSuggestSize_StringNonNumericInput_UsesDefault() {
        assertEquals(10, suggestTool.resolveSuggestSize("abc", 100), "non-numeric String must default to 10");
    }

    @Test
    public void testResolveSuggestSize_StringOverMax_CappedAtMax() {
        assertEquals(100, suggestTool.resolveSuggestSize("99999", 100), "numeric String exceeding max must be capped");
    }

    @Test
    public void testResolveSuggestSize_LongNumber_TruncatedToInt() {
        assertEquals(42, suggestTool.resolveSuggestSize(Long.valueOf(42L), 100), "Long within range must be preserved via intValue");
    }

    @Test
    public void testCall_MissingQuery() {
        try {
            suggestTool.call(Map.of(), new McpCallContext());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    @Test
    public void testCall_NonStringQuery_IsInvalidParams() {
        // getInputSchema() declares q as a string and was applied nowhere, so this used to reach
        // the unchecked cast and surface as "class java.lang.Integer cannot be cast to class
        // java.lang.String ..." in an isError:true result, instead of -32602.
        final McpError error = assertThrows(McpError.class, () -> suggestTool.call(Map.of("q", Integer.valueOf(1)), new McpCallContext()),
                "a wrong-typed q must be refused before the cast");
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode(), "the MCP spec requires -32602 for an invalid argument");
        assertEquals(200, error.getHttpStatus(), "an application-level failure stays HTTP 200");
        // "parameter: q", not a bare contains("q"): "required" contains "q".
        assertTrue(error.getMessage().contains("parameter: q"), "the message must name the argument: " + error.getMessage());
    }

    @Test
    public void testCall_NonNumericNumIsStillAccepted() {
        // Positive control for the deliberate gap: num is not type-checked because
        // resolveSuggestSize accepts any type by design, so a wrong-typed num must keep falling
        // back to the default rather than becoming a -32602. Past validation, call() reaches
        // ComponentUtil and throws, which is how far a container-free test can follow it.
        try {
            suggestTool.call(Map.of("q", "test", "num", Map.of("nested", "object")), new McpCallContext());
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "num must not be rejected before the container is reached");
        }
    }

    @Test
    public void testCall_RequiresDIContainer() {
        // Past the missing-query check, call() reaches ComponentUtil.getFessConfig() and throws.
        try {
            suggestTool.call(Map.of("q", "test"), new McpCallContext());
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }
}
