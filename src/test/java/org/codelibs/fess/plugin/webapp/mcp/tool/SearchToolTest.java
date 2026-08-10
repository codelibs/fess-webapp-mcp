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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.entity.SearchRequestParams;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
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

    @Test
    public void testBuildRequestParams_TypeIsJson() {
        // RoleQueryHelper relies on SearchRequestType.JSON to treat this as an API request and
        // apply Fess role filtering to MCP search results. If this ever regressed, results would
        // silently stop being permission-filtered. Building the params must not itself touch the
        // DI container, so this is safe to assert without one.
        final SearchRequestParams reqParams = searchTool.buildRequestParams(Map.of("q", "test"));
        assertEquals(SearchRequestParams.SearchRequestType.JSON, reqParams.getType());
    }
}
