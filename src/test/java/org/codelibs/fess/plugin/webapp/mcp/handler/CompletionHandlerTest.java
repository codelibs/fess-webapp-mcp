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
package org.codelibs.fess.plugin.webapp.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.codelibs.fess.suggest.entity.SuggestItem;
import org.codelibs.fess.suggest.request.suggest.SuggestRequestBuilder;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link CompletionHandler}.
 *
 * <p>
 * {@code testAdvancedSearchSortEmptyValueReturnsEveryAcceptedSortValue},
 * {@code testAdvancedSearchSortPrefixNoMatchReturnsEmpty},
 * {@code testAdvancedSearchKnownPromptButUnmatchedArgumentFallsThroughToEmptyCompletions},
 * {@code testCompleteViaSuggestRequiresDiContainer}, and the three
 * {@code testBuildCompletionResult*} cases migrated from the retired {@code McpApiManagerTest}
 * ({@code testHandleComplete_AdvancedSearchSort_EmptyValueReturnsAll},
 * {@code testHandleComplete_SortPrefix_NoMatch}, {@code testHandleComplete_AdvancedSearchNum_EmptyValues},
 * {@code testHandleComplete_WithValue_RequiresDIContainer}, and
 * {@code testBuildCompletionResult_Caps100_ValuesAndReflectsHasMore} /
 * {@code testBuildCompletionResult_ExactlyAtCap_HasMoreFalse} /
 * {@code testBuildCompletionResult_TotalLessThanValues_NormalizesTotal}) when {@code handleComplete}
 * and {@code buildCompletionResult} moved out of {@code McpApiManager} into this class.
 * </p>
 */
public class CompletionHandlerTest {

    /** Fess's shipped sort fields, in QueryFieldConfig order. */
    private static final String[] SHIPPED_SORT_FIELDS =
            { "score", "filename", "created", "content_length", "last_modified", "timestamp", "click_count", "favorite_count" };

    private final CompletionHandler handler = new CompletionHandler() {
        @Override
        protected String[] getSortableFields() {
            return SHIPPED_SORT_FIELDS;
        }
    };

    private McpCallContext contextWithParams(final Map<String, Object> params) {
        return new McpCallContext(null, null, params);
    }

    @Test
    public void testGetMethod() {
        assertEquals("completion/complete", handler.getMethod());
    }

    @Test
    public void testMissingRefIsInvalidParams() {
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(Map.of())));
        assertEquals(200, error.getHttpStatus());
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testMissingArgumentIsInvalidParams() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/prompt", "name", "basic_search"));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testEmptyValueReturnsEmptyCompletionsWithoutTouchingTheDiContainer() {
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "basic_search"), "argument", Map.of("name", "query", "value", ""));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertTrue(((List<String>) completion.get("values")).isEmpty());
        assertEquals(false, completion.get("hasMore"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchSortPrefixFilter() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/prompt", "name", "advanced_search"), "argument",
                Map.of("name", "sort", "value", "score"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        final List<String> values = (List<String>) completion.get("values");
        assertEquals(2, values.size());
        assertTrue(values.contains("score.desc"));
        assertTrue(values.contains("score.asc"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUnknownRefTypeReturnsEmptyCompletions() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/unknown"), "argument", Map.of("name", "x", "value", "y"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertTrue(((List<String>) completion.get("values")).isEmpty());
    }

    @Test
    public void testDoesNotSetTtlMsOrCacheScope() {
        // CompleteResult is not a CacheableResult in the 2026-07-28 schema.
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/unknown"), "argument", Map.of("name", "x", "value", ""));
        final Map<String, Object> result = handler.handle(contextWithParams(params));
        assertFalse(result.containsKey("ttlMs"));
        assertFalse(result.containsKey("cacheScope"));
    }

    @Test
    public void testResultIsMutable() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/unknown"), "argument", Map.of("name", "x", "value", ""));
        final Map<String, Object> result = handler.handle(contextWithParams(params));
        assertDoesNotThrow(() -> result.put("resultType", "complete"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchSortEmptyValueReturnsEveryAcceptedSortValue() {
        // An empty (or absent) sort prefix must return every candidate, not none.
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "advanced_search"), "argument", Map.of("name", "sort", "value", ""));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertEquals(SHIPPED_SORT_FIELDS.length * 2, ((List<String>) completion.get("values")).size());
        assertEquals(false, completion.get("hasMore"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSortCandidatesAreOnlyValuesTheSearchToolAccepts() {
        // The fixed list this replaced offered create_timestamp.desc/.asc, a field Fess does not
        // sort on: completing it produced a value the search tool then rejects.
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "advanced_search"), "argument", Map.of("name", "sort", "value", ""));
        final List<String> values =
                (List<String>) ((Map<String, Object>) handler.handle(contextWithParams(params)).get("completion")).get("values");
        final List<String> accepted = List.of(SHIPPED_SORT_FIELDS);
        for (final String value : values) {
            final int dot = value.lastIndexOf('.');
            assertTrue(dot > 0 && accepted.contains(value.substring(0, dot)), "not an accepted sort field: " + value);
            assertTrue(value.endsWith(".asc") || value.endsWith(".desc"), value);
        }
        assertFalse(values.stream().anyMatch(v -> v.startsWith("create_timestamp")), values.toString());
        assertEquals("score.desc", values.get(0), "score first, as Fess lists it");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testUnresolvableSortFieldsOfferNoCandidatesRatherThanInventedOnes() {
        // No container here, so the real getSortableFields() cannot resolve QueryFieldConfig.
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "advanced_search"), "argument", Map.of("name", "sort", "value", ""));
        final List<String> values =
                (List<String>) ((Map<String, Object>) new CompletionHandler().handle(contextWithParams(params)).get("completion"))
                        .get("values");
        assertTrue(values.isEmpty(), values.toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchSortPrefixNoMatchReturnsEmpty() {
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "advanced_search"), "argument", Map.of("name", "sort", "value", "zzz"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertTrue(((List<String>) completion.get("values")).isEmpty());
        assertEquals(false, completion.get("hasMore"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchKnownPromptButUnmatchedArgumentFallsThroughToEmptyCompletions() {
        // "num" is a real advanced_search argument, but completion is not implemented for it:
        // this must fall through to the ref/prompt branch's own empty-values default, a
        // different code path from the outer ref/resource-or-unknown-type default covered by
        // testUnknownRefTypeReturnsEmptyCompletions.
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "advanced_search"), "argument", Map.of("name", "num", "value", "1"));

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertTrue(((List<String>) completion.get("values")).isEmpty());
        assertEquals(false, completion.get("hasMore"));
    }

    @Test
    public void testCompleteViaSuggestRequiresDiContainer() {
        // Past the empty-value short-circuit, a non-empty query argument reaches
        // ComponentUtil.getSuggestHelper(), which needs a DI container this container-free
        // suite does not provide.
        final Map<String, Object> params =
                Map.of("ref", Map.of("type", "ref/prompt", "name", "basic_search"), "argument", Map.of("name", "query", "value", "test"));
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> handler.handle(contextWithParams(params)));
        assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAdvancedSearchSortMissingValueKeyTreatedAsEmptyPrefix() {
        // argument carries no "value" key at all (not even ""): argValueRaw is null and must be
        // coalesced to "" before it is read as a prefix. If the coalescing were removed,
        // getSortValues().stream().filter(v -> v.startsWith(null)) would NPE, unlike
        // testAdvancedSearchSortEmptyValueReturnsEveryAcceptedSortValue, whose explicit "" never exercises
        // the null branch of the coalescing at all.
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "advanced_search"));
        final Map<String, Object> argument = new HashMap<>();
        argument.put("name", "sort");
        // intentionally no "value" key
        params.put("argument", argument);

        final Map<String, Object> result = handler.handle(contextWithParams(params));

        final Map<String, Object> completion = (Map<String, Object>) result.get("completion");
        assertEquals(SHIPPED_SORT_FIELDS.length * 2, ((List<String>) completion.get("values")).size(),
                "a missing value key must behave like an empty prefix and return every sort value");
    }

    @Test
    public void testUnrecognisedPromptNameIsInvalidParamsWithoutTouchingSuggest() {
        // ref/prompt with an unrecognised prompt name, but a *known* query argument name and a
        // *non-empty* value. The completion page answers an invalid prompt name with -32602, and
        // the check must come before completeViaSuggest: if it did not, this call would reach
        // ComponentUtil.getSuggestHelper() and throw IllegalStateException instead.
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/prompt", "name", "totally_unknown_prompt"), "argument",
                Map.of("name", "query", "value", "something"));

        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals(200, error.getHttpStatus());
        assertEquals("Unknown prompt: totally_unknown_prompt", error.getMessage(), "the same wording prompts/get uses");
    }

    @Test
    public void testPromptReferenceWithoutANameIsInvalidParams() {
        final Map<String, Object> params = Map.of("ref", Map.of("type", "ref/prompt"), "argument", Map.of("name", "query", "value", "x"));
        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
    }

    @Test
    public void testBuildCompletionResultCapsAt100AndReflectsHasMore() {
        // buildCompletionResult must cap values at 100 and ensure hasMore reflects the fact
        // that the reported total exceeds the capped list size.
        final List<String> over = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            over.add("v" + i);
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) handler.buildCompletionResult(over, 150, false).get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) result.get("values");
        assertEquals(100, values.size(), "Values must be capped at 100");
        assertEquals(150, ((Number) result.get("total")).intValue(), "Total should be the original total");
        assertEquals(true, result.get("hasMore"), "hasMore must be true when total exceeds cap");
    }

    @Test
    public void testBuildCompletionResultExactlyAtCapHasMoreFalse() {
        final List<String> exact = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            exact.add("v" + i);
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) handler.buildCompletionResult(exact, 100, false).get("completion");
        @SuppressWarnings("unchecked")
        final List<String> values = (List<String>) result.get("values");
        assertEquals(100, values.size(), "Exactly 100 values must remain 100");
        assertEquals(100, ((Number) result.get("total")).intValue());
        assertEquals(false, result.get("hasMore"), "hasMore must be false when total equals capped size");
    }

    @Test
    public void testBuildCompletionResultNormalizesTotalWhenLessThanValues() {
        // Defensive: if the caller passes total < values.size(), total should be normalized
        // to at least values.size() so the envelope remains consistent.
        final List<String> three = List.of("a", "b", "c");
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) handler.buildCompletionResult(three, 0, false).get("completion");
        assertEquals(3, ((Number) result.get("total")).intValue(), "Total must be at least the number of values");
    }

    @Test
    public void testArgumentWithoutANameIsInvalidParamsWithoutDiAccess() {
        // argument is present but empty. CompleteRequestParams.argument requires name, and a
        // missing required argument is -32602 -- reported before anything reaches Fess suggest.
        final Map<String, Object> params = new HashMap<>();
        params.put("ref", Map.of("type", "ref/prompt", "name", "basic_search"));
        params.put("argument", Map.of());

        final McpError error = assertThrows(McpError.class, () -> handler.handle(contextWithParams(params)));
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode());
        assertEquals("Missing required parameter: argument.name", error.getMessage());
    }

    @Test
    public void testCompletionSuggestRequestCarriesTheCallersRoles() {
        // Same defect, same cause as SuggestTool: fess-suggest always filters on roles and falls
        // back to the "_guest_" sentinel that no indexed item ever carries, so a request built
        // without the caller's roles matches nothing and completion/complete returns an empty
        // list for every argument value. Pinned separately because the two call sites are
        // independent -- fixing only one leaves the other silently dead.
        final List<String> addedRoles = new ArrayList<>();
        final CompletionHandler h = new CompletionHandler() {
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

        h.buildSuggestRequest("mcp");

        assertEquals(List.of("Rguest", "1guest"), addedRoles, "completion/complete must scope its suggest request to the caller's roles");
    }

    @Test
    public void testCompletionSuggestRequestStillDeclaresBothItemKinds() {
        final List<String> addedKinds = new ArrayList<>();
        final CompletionHandler h = new CompletionHandler() {
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
                return Set.of();
            }
        };

        h.buildSuggestRequest("mcp");

        assertEquals(List.of(SuggestItem.Kind.QUERY.toString(), SuggestItem.Kind.DOCUMENT.toString()), addedKinds,
                "completion must ask for both query-log and document derived items");
    }
}
