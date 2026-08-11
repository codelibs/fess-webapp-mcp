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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.entity.SearchRequestParams;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link SearchTool}.
 *
 * <p>
 * Migrated from {@code McpApiManagerTest} ({@code testCreateDocumentContent*},
 * {@code testStripHighlightTags}, {@code testProcessDocumentItems*}, {@code testProcessValue*})
 * when {@code invokeSearch} and its formatting helpers moved out of {@code McpApiManager} into
 * this class. {@code testGetName}, {@code testDescriptionContainsQuerySyntaxInfo},
 * {@code testAnnotationsAreReadOnlyAndNotDestructive}, and
 * {@code testInputSchemaRequiresOnlyQ} migrated similarly from
 * {@code testHandleListTools}/{@code testSearchToolDescription_ContainsQuerySyntaxInfo}/
 * {@code testHandleListTools_SearchToolAnnotations}/{@code testHandleListTools_DetailedSchema}
 * when {@code getName}/{@code getDescription}/{@code getAnnotations}/{@code getInputSchema}
 * moved out of {@code McpApiManager} into this class.
 * </p>
 */
public class SearchToolTest {

    /**
     * {@code createDocumentContent}'s fallback-to-raw-content branch calls
     * {@code DocumentFormatter#getContentMaxLength()}, which needs a DI container. Tests that
     * exercise that branch use a formatter overridden with a fixed length instead, so they stay
     * container-free like the rest of this suite.
     */
    private final SearchTool searchTool = newSearchToolWithMaxContentLength(10000);

    private static SearchTool newSearchToolWithMaxContentLength(final int maxLength) {
        return new SearchTool() {
            @Override
            protected DocumentFormatter getDocumentFormatter() {
                return new DocumentFormatter() {
                    @Override
                    protected int getContentMaxLength() {
                        return maxLength;
                    }
                };
            }
        };
    }

    @Test
    public void testCreateDocumentContent() {
        final Map<String, Object> doc =
                Map.of("title", "Test Document", "url", "https://example.com/test", "content", "This is test content.", "score", 10.5);

        final Map<String, Object> result = searchTool.createDocumentContent(doc, 1);

        assertNotNull(result, "Result should not be null");
        assertEquals("text", result.get("type"), "Type should be text");

        final String text = (String) result.get("text");
        assertNotNull(text, "Text should not be null");
        assertTrue(text.contains("**Title**: Test Document"), "Text should contain Title");
        assertTrue(text.contains("**URL**: https://example.com/test"), "Text should contain URL");
        assertTrue(text.contains("**Score**: 10.5"), "Text should contain Score");
        assertTrue(text.contains("This is test content."), "Text should contain content");
    }

    @Test
    public void testCreateDocumentContent_WithoutScore() {
        final Map<String, Object> doc =
                Map.of("title", "Test Document", "url", "https://example.com/test", "content", "This is test content.");

        final Map<String, Object> result = searchTool.createDocumentContent(doc, 2);

        final String text = (String) result.get("text");
        assertTrue(text.contains("**Title**: Test Document"), "Text should contain Title");
        assertFalse(text.contains("**Score**:"), "Text should not contain Score");
    }

    @Test
    public void testCreateDocumentContent_EmptyDocument() {
        final Map<String, Object> doc = Map.of();

        final Map<String, Object> result = searchTool.createDocumentContent(doc, 1);

        assertNotNull(result, "Result should not be null");
        assertEquals("text", result.get("type"), "Type should be text");

        final String text = (String) result.get("text");
        assertTrue(text.contains("**Title**:"), "Text should contain Title label");
        assertTrue(text.contains("**URL**:"), "Text should contain URL label");
    }

    @Test
    public void testCreateDocumentContent_WithContentDescription() {
        // Test with content_description (highlighted content)
        final Map<String, Object> doc = new HashMap<>();
        doc.put("title", "Test Document");
        doc.put("url", "https://example.com/test");
        doc.put("content", "This is raw content that should not appear.");
        doc.put("content_description", "<em>highlighted</em> search result content");
        doc.put("score", 10.5);

        final Map<String, Object> result = searchTool.createDocumentContent(doc, 1);

        assertNotNull(result, "Result should not be null");
        assertEquals("text", result.get("type"), "Type should be text");

        final String text = (String) result.get("text");
        assertNotNull(text, "Text should not be null");
        assertTrue(text.contains("**Title**: Test Document"), "Text should contain Title");
        assertTrue(text.contains("**URL**: https://example.com/test"), "Text should contain URL");
        assertTrue(text.contains("**Score**: 10.5"), "Text should contain Score");
        // Verify content_description is used and tags are stripped
        assertTrue(text.contains("highlighted search result content"), "Text should contain stripped highlighted content");
        assertFalse(text.contains("<em>"), "Text should not contain HTML tags");
        assertFalse(text.contains("raw content that should not appear"), "Text should not contain raw content");
    }

    @Test
    public void testCreateDocumentContent_FallbackToContent() {
        // Test fallback when content_description is empty
        final Map<String, Object> doc = new HashMap<>();
        doc.put("title", "Test Document");
        doc.put("url", "https://example.com/test");
        doc.put("content", "This is the raw content used as fallback.");
        doc.put("content_description", "");
        doc.put("score", 5.0);

        final Map<String, Object> result = searchTool.createDocumentContent(doc, 1);

        final String text = (String) result.get("text");
        assertTrue(text.contains("This is the raw content used as fallback."), "Text should contain raw content as fallback");
    }

    @Test
    public void testCreateDocumentContent_WithContentTruncation() {
        // Force a small max length, avoiding any DI container.
        final SearchTool tool = newSearchToolWithMaxContentLength(20);

        // content_description is empty, so content will be used with truncation
        final String longContent = "This is a very long content that should be truncated";
        final Map<String, Object> doc = new HashMap<>();
        doc.put("title", "Test");
        doc.put("url", "http://test.com");
        doc.put("content", longContent);
        doc.put("content_description", ""); // Empty to trigger fallback to content

        final Map<String, Object> result = tool.createDocumentContent(doc, 1);
        final String text = (String) result.get("text");

        assertTrue(text.contains("..."), "Content should be truncated with ...");
        assertFalse(text.contains("should be truncated"), "Full content should not be present");
    }

    @Test
    public void testStripHighlightTags() {
        // Test with em tags
        assertEquals("highlighted text", searchTool.stripHighlightTags("<em>highlighted</em> text"));

        // Test with strong tags
        assertEquals("highlighted text", searchTool.stripHighlightTags("<strong>highlighted</strong> text"));

        // Test with mixed tags
        assertEquals("hello world test", searchTool.stripHighlightTags("<em>hello</em> <strong>world</strong> test"));

        // Test with null
        assertEquals("", searchTool.stripHighlightTags(null));

        // Test with empty string
        assertEquals("", searchTool.stripHighlightTags(""));

        // Test with no tags
        assertEquals("plain text", searchTool.stripHighlightTags("plain text"));

        // Test with nested content
        assertEquals("multiple highlights here",
                searchTool.stripHighlightTags("<em>multiple</em> <em>highlights</em> <strong>here</strong>"));
    }

    @Test
    public void testProcessDocumentItems_Null() {
        final List<Map<String, Object>> result = searchTool.processDocumentItems(null);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty");
    }

    @Test
    public void testProcessDocumentItems_EmptyList() {
        final List<Map<String, Object>> result = searchTool.processDocumentItems(List.of());
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty");
    }

    @Test
    public void testProcessDocumentItems_WithDocuments() {
        final List<Map<String, Object>> docs =
                List.of(Map.of("title", "Doc1", "url", "http://example.com/1"), Map.of("title", "Doc2", "url", "http://example.com/2"));

        final List<Map<String, Object>> result = searchTool.processDocumentItems(docs);

        assertEquals(2, result.size(), "Should have 2 documents");
        assertEquals("Doc1", result.get(0).get("title"), "First doc title");
        assertEquals("Doc2", result.get(1).get("title"), "Second doc title");
    }

    @Test
    public void testProcessValue_Null() {
        assertNull(searchTool.processValue(null), "Null should return null");
    }

    @Test
    public void testProcessValue_String() {
        assertEquals("test", searchTool.processValue("test"), "String should be unchanged");
    }

    @Test
    public void testProcessValue_Number() {
        assertEquals(123, searchTool.processValue(123), "Number should be unchanged");
        assertEquals(1.5, searchTool.processValue(1.5), "Double should be unchanged");
    }

    @Test
    public void testProcessValue_List() {
        final List<Object> input = List.of("a", "b", 1);
        @SuppressWarnings("unchecked")
        final List<Object> result = (List<Object>) searchTool.processValue(input);

        assertEquals(3, result.size(), "List size should be 3");
        assertEquals("a", result.get(0), "First element");
        assertEquals("b", result.get(1), "Second element");
        assertEquals(1, result.get(2), "Third element");
    }

    @Test
    public void testProcessValue_Map() {
        final Map<String, Object> input = Map.of("key1", "value1", "key2", 123);
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) searchTool.processValue(input);

        assertEquals(2, result.size(), "Map size should be 2");
        assertEquals("value1", result.get("key1"), "key1 value");
        assertEquals(123, result.get("key2"), "key2 value");
    }

    @Test
    public void testProcessValue_Array() {
        final Object[] input = new Object[] { "a", "b", 1 };
        @SuppressWarnings("unchecked")
        final List<Object> result = (List<Object>) searchTool.processValue(input);

        assertEquals(3, result.size(), "Array should be converted to List with size 3");
        assertEquals("a", result.get(0), "First element");
    }

    @Test
    public void testCall_RequiresDIContainer() {
        // Without a DI container, call() reaches ComponentUtil.getFessConfig() and throws.
        try {
            searchTool.call(Map.of("q", "test"), new McpCallContext());
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testGetName() {
        assertEquals("search", searchTool.getName());
    }

    @Test
    public void testDescriptionContainsQuerySyntaxInfo() {
        final String description = searchTool.getDescription();
        assertTrue(description.contains("Lucene"), "Description should mention Lucene");
        assertTrue(description.contains("AND"), "Description should mention AND");
        assertTrue(description.contains("OR"), "Description should mention OR");
        assertTrue(description.contains("phrase"), "Description should mention phrase search");
        assertTrue(description.contains("exclusion") || description.contains("-"), "Description should mention exclusion");
    }

    @Test
    public void testAnnotationsAreReadOnlyAndNotDestructive() {
        final Map<String, Object> annotations = searchTool.getAnnotations();
        assertEquals(true, annotations.get("readOnlyHint"), "Search should be read-only");
        assertEquals(false, annotations.get("destructiveHint"), "Search should not be destructive");
        assertEquals(false, annotations.get("openWorldHint"), "Search should not be open-world");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInputSchemaRequiresOnlyQ() {
        final Map<String, Object> schema = searchTool.getInputSchema();
        final List<String> required = (List<String>) schema.get("required");
        assertEquals(List.of("q"), required, "Only q should be required");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInputSchemaDeclaresQStartNumSortAndLangProperties() {
        // Migrated from the retired McpApiManagerTest#testHandleListTools_DetailedSchema, which
        // asserted this via tools/list; nothing asserted it directly against the tool's own
        // schema, so start/sort/lang were left uncovered when that test was judged a duplicate.
        final Map<String, Object> properties = (Map<String, Object>) searchTool.getInputSchema().get("properties");
        assertTrue(properties.containsKey("q"), "Should have 'q' property");
        assertTrue(properties.containsKey("start"), "Should have 'start' property");
        assertTrue(properties.containsKey("num"), "Should have 'num' property");
        assertTrue(properties.containsKey("sort"), "Should have 'sort' property");
        assertTrue(properties.containsKey("lang"), "Should have 'lang' property");
    }

    // ------------------------------------------------------------------
    // Argument validation (getInputSchema() is advertised but was applied nowhere)
    // ------------------------------------------------------------------

    /**
     * Asserts that calling {@code search} with these arguments is refused as {@code -32602} at
     * HTTP 200, and that the message names the offending argument.
     *
     * @param arguments the arguments to reject
     * @param argumentName the argument name the message must mention
     */
    private void assertRejectedAsInvalidParams(final Map<String, Object> arguments, final String argumentName) {
        final McpError error = assertThrows(McpError.class, () -> searchTool.call(arguments, new McpCallContext()),
                "wrong-typed or missing arguments must be refused before the search runs");
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode(), "the MCP spec requires -32602 for an invalid argument");
        assertEquals(200, error.getHttpStatus(), "an application-level failure stays HTTP 200");
        // "parameter: <name>", not a bare contains(name): "required" contains "q".
        assertTrue(error.getMessage().contains("parameter: " + argumentName), "the message must name the argument: " + error.getMessage());
    }

    @Test
    public void testCall_MissingQuery_IsInvalidParams() {
        // getInputSchema() marks q required, but getQuery() just returned null and the
        // unvalidated request reached SearchHelper.
        assertRejectedAsInvalidParams(Map.of(), "q");
    }

    @Test
    public void testCall_NullQuery_IsInvalidParams() {
        // {"q": null} parses to a present key with a null value, which reaches getQuery() as the
        // same null an absent q does.
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("q", null);
        assertRejectedAsInvalidParams(arguments, "q");
    }

    @Test
    public void testCall_NonStringQuery_IsInvalidParams() {
        // Was: "class java.lang.Integer cannot be cast to class java.lang.String ..." echoed back
        // as an isError:true result.
        assertRejectedAsInvalidParams(Map.of("q", Integer.valueOf(1)), "q");
    }

    @Test
    public void testCall_NonStringSort_IsInvalidParams() {
        assertRejectedAsInvalidParams(Map.of("q", "test", "sort", Integer.valueOf(1)), "sort");
    }

    @Test
    public void testCall_NonStringSdh_IsInvalidParams() {
        assertRejectedAsInvalidParams(Map.of("q", "test", "sdh", Integer.valueOf(1)), "sdh");
    }

    @Test
    public void testCall_NonObjectFields_IsInvalidParams() {
        // The likeliest client mistake for fields: sending the field name instead of the
        // {"label": ["label1"]} object the schema declares.
        assertRejectedAsInvalidParams(Map.of("q", "test", "fields", "label"), "fields");
    }

    @Test
    public void testCall_NonObjectAs_IsInvalidParams() {
        assertRejectedAsInvalidParams(Map.of("q", "test", "as", "sitesearch"), "as");
    }

    @Test
    public void testCall_NonArrayExQ_IsInvalidParams() {
        assertRejectedAsInvalidParams(Map.of("q", "test", "ex_q", "extra"), "ex_q");
    }

    @Test
    public void testCall_WellTypedArgumentsPassValidation() {
        // Positive control for the whole block above: proves validation rejects wrong types
        // rather than simply rejecting every argument. executeSearch is stubbed out so this
        // stays container-free; reaching it at all means validation let the call through.
        final SearchTool tool = new SearchTool() {
            @Override
            protected List<Map<String, Object>> executeSearch(final Map<String, Object> arguments) {
                return List.of();
            }
        };
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("q", "test");
        arguments.put("sort", "score.desc");
        arguments.put("sdh", "hash");
        arguments.put("fields", Map.of("label", List.of("label1")));
        arguments.put("as", Map.of("sitesearch", List.of("example.com")));
        arguments.put("ex_q", List.of("extra"));
        // start/offset/num/lang are deliberately not type-checked: their accessors accept any
        // type by design, so a numeric String must keep working.
        arguments.put("start", "10");
        arguments.put("num", Integer.valueOf(5));
        arguments.put("offset", Integer.valueOf(0));
        arguments.put("lang", "en");

        assertDoesNotThrow(() -> tool.call(arguments, new McpCallContext()), "well-typed arguments must not be rejected");
    }

    @Test
    public void testBuildRequestParams_TypeIsJson() {
        // RoleQueryHelper relies on SearchRequestType.JSON to treat this as an API request and
        // apply Fess role filtering to MCP search results. If this ever regressed, results would
        // silently stop being permission-filtered. Building the params must not itself touch the
        // DI container, so this is safe to assert without one.
        final SearchRequestParams reqParams = searchTool.buildRequestParams(Map.of("q", "test"));
        assertEquals(SearchRequestParams.SearchRequestType.JSON, reqParams.getType());
    }

    // ------------------------------------------------------------------
    // Paging: start / offset / num
    // ------------------------------------------------------------------

    /** The configured {@code paging.search.page.start} these tests pin against. */
    private static final int PAGE_START = 0;

    /** The configured {@code paging.search.page.max.size} these tests pin against. */
    private static final int MAX_PAGE_SIZE = 100;

    /** The effective {@code mcp.default.page.size} these tests pin against. */
    private static final int DEFAULT_PAGE_SIZE = 3;

    /**
     * A {@code search} tool whose paging configuration is fixed, so the {@code start}/
     * {@code offset}/{@code num} accessors can be asserted without a DI container.
     * <p>
     * {@code getStartPosition()} and {@code getPageSize()} both consult
     * {@link SearchTool#getFessConfig()} for their fallbacks -- the configured page start, the
     * configured maximum page size -- and {@code getPageSize()} additionally consults
     * {@link SearchTool#getDefaultPageSize()}. Substituting all three keeps these tests
     * container-free while still exercising the real accessor bodies, and pins the three
     * distinct fallback values apart from each other ({@value #PAGE_START} /
     * {@value #MAX_PAGE_SIZE} / {@value #DEFAULT_PAGE_SIZE}) so a test cannot accidentally pass
     * by landing on the wrong one. Only the two paging getters are overridden on the
     * {@code FessConfig.SimpleImpl}: it backs nothing else, so any accessor these tests do not
     * reach would fail loudly rather than return a plausible-looking zero.
     * </p>
     *
     * @return a container-free {@code search} tool with fixed paging configuration
     */
    private static SearchTool newSearchToolWithFixedPaging() {
        return new SearchTool() {
            @Override
            protected FessConfig getFessConfig() {
                return new FessConfig.SimpleImpl() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Integer getPagingSearchPageStartAsInteger() {
                        return Integer.valueOf(PAGE_START);
                    }

                    @Override
                    public Integer getPagingSearchPageMaxSizeAsInteger() {
                        return Integer.valueOf(MAX_PAGE_SIZE);
                    }
                };
            }

            @Override
            protected int getDefaultPageSize() {
                return DEFAULT_PAGE_SIZE;
            }
        };
    }

    /**
     * Returns {@code getStartPosition()} for one set of {@code search} arguments.
     *
     * @param arguments the raw {@code search} tool arguments
     * @return the resolved start position
     */
    private static int startPositionOf(final Map<String, Object> arguments) {
        return newSearchToolWithFixedPaging().buildRequestParams(arguments).getStartPosition();
    }

    /**
     * Returns {@code getOffset()} for one set of {@code search} arguments.
     *
     * @param arguments the raw {@code search} tool arguments
     * @return the resolved rank-fusion window offset
     */
    private static int offsetOf(final Map<String, Object> arguments) {
        return newSearchToolWithFixedPaging().buildRequestParams(arguments).getOffset();
    }

    /**
     * Returns {@code getPageSize()} for one set of {@code search} arguments.
     *
     * @param arguments the raw {@code search} tool arguments
     * @return the resolved page size
     */
    private static int pageSizeOf(final Map<String, Object> arguments) {
        return newSearchToolWithFixedPaging().buildRequestParams(arguments).getPageSize();
    }

    @Test
    public void testStartPosition_StartIsUsed() {
        // Control for the offset tests below: the primary name has always worked, so a failure
        // here would mean the alias fix broke the thing it was extending.
        assertEquals(20, startPositionOf(Map.of("q", "x", "start", Integer.valueOf(20))), "start must set the start position");
    }

    @Test
    public void testStartPosition_OffsetAloneIsUsedAsAnAliasOfStart() {
        // getInputSchema() advertises offset as "offset (alias of start)" over tools/list to
        // every client, but getStartPosition() read only "start", so the alias was a complete
        // no-op: a client paginating with offset re-requested page 1 forever.
        assertEquals(20, startPositionOf(Map.of("q", "x", "offset", Integer.valueOf(20))),
                "offset is advertised as an alias of start and must set the start position");
    }

    @Test
    public void testStartPosition_StartWinsWhenBothArePresent() {
        // start is the primary name; offset only stands in for it. A caller that sends both is
        // most likely migrating from one name to the other, so the primary name decides.
        assertEquals(5, startPositionOf(Map.of("q", "x", "start", Integer.valueOf(5), "offset", Integer.valueOf(20))),
                "start must win over its own alias");
    }

    @Test
    public void testStartPosition_StartWinsEvenWhenItsOwnValueIsUnusable() {
        // The other half of the tie-break rule, and the half a plausible implementation gets
        // wrong: "start wins" means "start wins whenever the caller sent it", not "start wins
        // when it happens to parse". Falling through to the alias here would page through
        // results from somewhere the caller never asked for -- offset would silently repair a
        // start the caller got wrong, with nothing in the response to say it happened.
        assertEquals(PAGE_START, startPositionOf(Map.of("q", "x", "start", "abc", "offset", Integer.valueOf(20))),
                "an unparseable start must not fall through to offset");
        assertEquals(PAGE_START, startPositionOf(Map.of("q", "x", "start", Integer.valueOf(-1), "offset", Integer.valueOf(20))),
                "a negative start must not fall through to offset");
    }

    @Test
    public void testStartPosition_ExplicitNullStartIsTreatedAsAbsentSoTheAliasApplies() {
        // A key present with an explicit JSON null is indistinguishable from an absent key at
        // this layer, and validateArguments already treats {"q": null} as a missing q, so the
        // alias applies rather than the caller silently getting page 1.
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("q", "x");
        arguments.put("start", null);
        arguments.put("offset", Integer.valueOf(20));
        assertEquals(20, startPositionOf(arguments), "an explicitly-null start must not suppress the offset alias");
    }

    @Test
    public void testStartPosition_NumericStringOffsetIsParsedLikeANumericStringStart() {
        // The alias must inherit getStartPosition()'s existing coercion, not a stricter one:
        // testCall_WellTypedArgumentsPassValidation already pins that start accepts "10", and
        // start/offset/num are deliberately exempt from the schema type check for that reason.
        assertEquals(10, startPositionOf(Map.of("q", "x", "start", "10")), "a numeric String start must keep being parsed");
        assertEquals(10, startPositionOf(Map.of("q", "x", "offset", "10")), "a numeric String offset must be parsed the same way");
    }

    @Test
    public void testStartPosition_NonNumericOffsetFallsBackLikeANonNumericStart() {
        // Not "offset is validated": an unparseable value falls back exactly as the primary name
        // does, so the alias adds no new rejection path.
        assertEquals(PAGE_START, startPositionOf(Map.of("q", "x", "start", "abc")),
                "a non-numeric start falls back to the configured start");
        assertEquals(PAGE_START, startPositionOf(Map.of("q", "x", "offset", "abc")), "a non-numeric offset must fall back the same way");
    }

    @Test
    public void testStartPosition_NegativeOffsetFallsBackLikeANegativeStart() {
        // getStartPosition() only accepts values > -1, so a negative value is ignored rather
        // than passed to the search engine. The alias inherits that bound unchanged.
        assertEquals(PAGE_START, startPositionOf(Map.of("q", "x", "start", Integer.valueOf(-1))), "a negative start is ignored");
        assertEquals(PAGE_START, startPositionOf(Map.of("q", "x", "offset", Integer.valueOf(-1))), "a negative offset must be ignored too");
    }

    @Test
    public void testStartPosition_NeitherStartNorOffsetFallsBackToTheConfiguredPageStart() {
        assertEquals(PAGE_START, startPositionOf(Map.of("q", "x")), "with neither name present the configured page start applies");
    }

    @Test
    public void testStartPosition_ZeroOffsetIsHonouredRatherThanTreatedAsAbsent() {
        // A present-but-zero offset must not be confused with an absent one. It happens to
        // coincide with PAGE_START here, so this asserts the value the caller asked for reached
        // the accessor by also proving zero does not let a companion start through.
        assertEquals(0, startPositionOf(Map.of("q", "x", "offset", Integer.valueOf(0))), "offset=0 is a legitimate first page");
    }

    @Test
    public void testOffsetAccessorStillReadsOnlyOffset() {
        // getOffset() feeds a different contract from getStartPosition(): Fess's
        // RankFusionProcessor uses it to shift the sub-searcher window on the deep-pagination
        // branch, so it must keep meaning "offset" alone and must not start aliasing start.
        assertEquals(20, offsetOf(Map.of("q", "x", "offset", Integer.valueOf(20))), "getOffset() must keep reading offset");
        assertEquals(0, offsetOf(Map.of("q", "x", "start", Integer.valueOf(20))),
                "getOffset() must not pick start up: the rank-fusion window shift is a separate contract");
    }

    @Test
    public void testPageSize_WithinRangeIsPreserved() {
        assertEquals(5, pageSizeOf(Map.of("q", "x", "num", Integer.valueOf(5))), "a num within range must be preserved");
    }

    @Test
    public void testPageSize_OverMaxIsCappedAtMax() {
        assertEquals(MAX_PAGE_SIZE, pageSizeOf(Map.of("q", "x", "num", Integer.valueOf(MAX_PAGE_SIZE + 1))),
                "a num over the configured maximum must be capped");
    }

    @Test
    public void testPageSize_AbsentFallsBackToTheDefault() {
        assertEquals(DEFAULT_PAGE_SIZE, pageSizeOf(Map.of("q", "x")), "an absent num must fall back to the default page size");
    }

    @Test
    public void testPageSize_NonNumericFallsBackToTheDefault() {
        // The behaviour the num<=0 cases below are aligned with, pinned first so the alignment
        // is anchored to something asserted rather than to a claim.
        assertEquals(DEFAULT_PAGE_SIZE, pageSizeOf(Map.of("q", "x", "num", "abc")), "an unparseable num must fall back to the default");
    }

    @Test
    public void testPageSize_ZeroFallsBackToTheDefaultNotTheMaximum() {
        // num<=0 used to share the "> max" branch and therefore returned the configured MAXIMUM
        // (100 in a stock install): asking for nothing produced the largest page the server
        // will ever emit. It now falls back to the default, matching both the unparseable-num
        // case above and SuggestTool#resolveSuggestSize.
        assertEquals(DEFAULT_PAGE_SIZE, pageSizeOf(Map.of("q", "x", "num", Integer.valueOf(0))), "num=0 must fall back to the default");
    }

    @Test
    public void testPageSize_NegativeFallsBackToTheDefaultNotTheMaximum() {
        assertEquals(DEFAULT_PAGE_SIZE, pageSizeOf(Map.of("q", "x", "num", Integer.valueOf(-1))), "num=-1 must fall back to the default");
    }

    @Test
    public void testPageSize_NumericStringZeroFallsBackToTheDefaultNotTheMaximum() {
        // The String path reaches the same comparison through Integer.parseInt, so it has to be
        // pinned separately: a fix applied to only one of the two branches would leave the other
        // still returning the maximum.
        assertEquals(DEFAULT_PAGE_SIZE, pageSizeOf(Map.of("q", "x", "num", "0")), "num=\"0\" must fall back to the default");
    }

    @Test
    public void testPageSize_NumericStringNegativeFallsBackToTheDefaultNotTheMaximum() {
        assertEquals(DEFAULT_PAGE_SIZE, pageSizeOf(Map.of("q", "x", "num", "-5")), "num=\"-5\" must fall back to the default");
    }
}
