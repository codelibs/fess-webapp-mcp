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

import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link SuggestTool}.
 *
 * <p>
 * Migrated from {@code McpApiManagerTest} ({@code testResolveSuggestSize*}) when
 * {@code invokeSuggest} and {@code resolveSuggestSize} moved out of {@code McpApiManager} into
 * this class.
 * </p>
 */
public class SuggestToolTest {

    private final SuggestTool suggestTool = new SuggestTool();

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
