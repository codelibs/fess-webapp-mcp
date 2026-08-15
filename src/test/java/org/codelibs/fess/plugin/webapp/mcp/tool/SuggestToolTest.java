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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.suggest.entity.SuggestItem;
import org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder;
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
    public void testResolveSuggestSize_StringZero_UsesDefault() {
        // The String path reaches the num <= 0 clamp through Integer.parseInt rather than
        // intValue(), so it needs its own assertion: the two tests above would both stay green
        // if the clamp were moved into the Number branch alone.
        assertEquals(10, suggestTool.resolveSuggestSize("0", 100), "num=\"0\" should fall back to default 10");
    }

    @Test
    public void testResolveSuggestSize_StringNegative_UsesDefault() {
        assertEquals(10, suggestTool.resolveSuggestSize("-5", 100), "num=\"-5\" should fall back to default 10");
    }

    @Test
    public void testResolveSuggestSize_NonPositiveMatchesSearchToolsFallbackDirection() {
        // Cross-tool alignment, pinned here as well as in SearchToolTest because the two used to
        // disagree: SearchTool#getPageSize() sent num <= 0 to the configured MAXIMUM page size
        // while this tool sent it to its own default, so the same {"num": 0} meant "as many as
        // possible" to search and "the default handful" to suggest. search was changed to match
        // this tool; if someone ever reverses that by "fixing" this one instead, the two drift
        // apart again silently. maxPageSize is passed deliberately larger than the default, so a
        // regression to the cap direction cannot land on 10 by coincidence.
        assertEquals(10, suggestTool.resolveSuggestSize(Integer.valueOf(0), 100),
                "num<=0 must resolve to the default, never to the maximum page size");
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
    public void testCall_EmptyQuery() {
        // The isEmpty() half of the q guard, which nothing exercised: testCall_MissingQuery
        // above only covers the null half, so deleting {@code || query.isEmpty()} left the whole
        // suite green while {"q": ""} went on to build a suggest request with an empty prefix.
        // Follows GetDocumentToolTest#testCall_EmptyDocId, which already covers the same shape
        // of guard for doc_id.
        try {
            suggestTool.call(Map.of("q", ""), new McpCallContext());
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

    @Test
    public void testSuggestRequestCarriesTheCallersRoles() {
        // fess-suggest applies a role filter to every suggest request whether or not the caller
        // added roles: SuggestRequest#buildFilterQuery injects SuggestConstants.DEFAULT_ROLE
        // ("_guest_") when the role list is empty. That sentinel is only ever stored on an item
        // built with no roles at all (SuggestItem's constructor), and Fess always indexes suggest
        // items with the source document's roles, so a role-less request matches nothing at all.
        // Fess's own SuggestWordsHandler adds the caller's roles; this tool must do the same or
        // it returns an empty list for every query in every deployment.
        final List<String> addedRoles = new ArrayList<>();
        final SuggestTool tool = new SuggestTool() {
            @Override
            protected SuggestRequestBuilder newSuggestRequestBuilder() {
                return new SuggestRequestBuilder(null, null, null) {
                    @Override
                    public SuggestRequestBuilder addRole(final String role) {
                        addedRoles.add(role);
                        return this;
                    }
                };
            }

            @Override
            protected Set<String> getCallerRoles() {
                return new LinkedHashSet<>(List.of("Rguest", "1guest"));
            }
        };

        tool.buildSuggestRequest("padding", 10);

        assertEquals(List.of("Rguest", "1guest"), addedRoles, "every role the caller may search with must be added to the suggest request");
    }

    @Test
    public void testSuggestRequestStillDeclaresBothItemKinds() {
        // Guards the refactor that introduced buildSuggestRequest: dropping either kind would
        // silently narrow suggest to query-log-only or document-only results.
        final List<String> addedKinds = new ArrayList<>();
        final SuggestTool tool = new SuggestTool() {
            @Override
            protected SuggestRequestBuilder newSuggestRequestBuilder() {
                return new SuggestRequestBuilder(null, null, null) {
                    @Override
                    public SuggestRequestBuilder addKind(final String kind) {
                        addedKinds.add(kind);
                        return this;
                    }
                };
            }

            @Override
            protected Set<String> getCallerRoles() {
                return Collections.emptySet();
            }
        };

        tool.buildSuggestRequest("padding", 10);

        assertEquals(List.of(SuggestItem.Kind.QUERY.toString(), SuggestItem.Kind.DOCUMENT.toString()), addedKinds,
                "suggest must ask for both query-log and document derived items");
    }
}
