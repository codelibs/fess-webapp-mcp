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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.entity.SearchRenderData;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.junit.jupiter.api.Test;

/**
 * Verifies that each {@link McpTool}'s {@code outputSchema} marks only always-present Fess
 * fields as {@code required}, and that the {@code structuredContent} each tool's {@code call}
 * actually returns conforms to that same schema -- for a minimal result carrying only mandatory
 * fields, and for one carrying every optional field too.
 */
public class OutputSchemaConformanceTest {

    // ------------------------------------------------------------------
    // A small, hand-rolled JSON Schema conformance check. This repo has no JSON Schema
    // validator dependency; the schemas under test are simple enough (object/array/string/
    // number/integer, "required", "additionalProperties") that a generic library would be
    // over-engineering for what this suite needs.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void assertConforms(final Map<String, Object> schema, final Object value, final String path) {
        final String type = (String) schema.get("type");
        assertNotNull(type, path + " schema must declare a type");
        switch (type) {
        case "object" -> {
            assertTrue(value instanceof Map, path + " must be an object, was " + describe(value));
            final Map<String, Object> map = (Map<String, Object>) value;
            final Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            final List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            for (final String req : required) {
                assertTrue(map.containsKey(req), path + " is missing required property '" + req + "'");
            }
            // Every present value must be non-null: structuredContent must have nulls
            // stripped, regardless of whether the property is required.
            for (final Map.Entry<String, Object> entry : map.entrySet()) {
                assertNotNull(entry.getValue(), path + "." + entry.getKey() + " must not be null (nulls must be stripped)");
            }
            if (!(boolean) schema.getOrDefault("additionalProperties", true)) {
                for (final String key : map.keySet()) {
                    assertTrue(properties.containsKey(key), path + " has undeclared property '" + key + "'");
                }
            }
            for (final Map.Entry<String, Object> entry : map.entrySet()) {
                if (properties.containsKey(entry.getKey())) {
                    assertConforms((Map<String, Object>) properties.get(entry.getKey()), entry.getValue(), path + "." + entry.getKey());
                }
            }
        }
        case "array" -> {
            assertTrue(value instanceof List, path + " must be an array, was " + describe(value));
            final Map<String, Object> items = (Map<String, Object>) schema.get("items");
            final List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                assertConforms(items, list.get(i), path + "[" + i + "]");
            }
        }
        case "string" -> assertTrue(value instanceof String, path + " must be a string, was " + describe(value));
        case "number" -> assertTrue(value instanceof Number, path + " must be a number, was " + describe(value));
        case "integer" -> assertTrue(value instanceof Integer || value instanceof Long,
                path + " must be an integer, was " + describe(value));
        case "boolean" -> assertTrue(value instanceof Boolean, path + " must be a boolean, was " + describe(value));
        default -> fail("unsupported schema type under test: " + type);
        }
    }

    private static String describe(final Object value) {
        return value == null ? "null" : value.getClass().getName() + "(" + value + ")";
    }

    /**
     * A {@link SearchTool} whose {@link SearchTool#executeSearch} seam returns {@code docs}
     * without touching the DI container, and whose {@link DocumentFormatter} is likewise
     * container-free -- needed because {@code createDocumentContent}'s fallback-to-raw-content
     * branch calls {@code DocumentFormatter#getContentMaxLength()} whenever a doc has no
     * {@code content_description}, exactly like {@code SearchToolTest} already does.
     */
    private static SearchTool searchToolReturning(final List<Map<String, Object>> docs) {
        return new ContainerFreeSearchTool() {
            @Override
            protected List<Map<String, Object>> executeSearch(final Map<String, Object> arguments, final SearchRenderData data) {
                return docs;
            }

            @Override
            protected DocumentFormatter getDocumentFormatter() {
                return new DocumentFormatter() {
                    @Override
                    protected int getContentMaxLength() {
                        return 10000;
                    }
                };
            }
        };
    }

    // ------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testSearchOutputSchemaHasNoRequiredOptionalFields() {
        final Map<String, Object> schema = new SearchTool().getOutputSchema();
        assertEquals("object", schema.get("type"));
        final Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("hits"));
        final Map<String, Object> hit = (Map<String, Object>) ((Map<String, Object>) properties.get("hits")).get("items");
        final List<String> required = (List<String>) hit.getOrDefault("required", List.of());
        assertFalse(required.contains("title"), "title can be entirely absent from a Fess document's _source");
        assertFalse(required.contains("url"), "url can be entirely absent from a Fess document's _source");
        assertFalse(required.contains("score"), "score is absent for some Fess results (e.g. non-finite relevance scores)");
        assertFalse(required.contains("content_description"),
                "content_description is kept optional out of caution across rank-fusion/hybrid search paths");
        assertFalse(required.contains("doc_id"),
                "doc_id stays optional: Fess only generates one when the crawler found none already on the document, "
                        + "so a data-store or script configured id can leave it absent");
    }

    @Test
    public void testSearchOutputSchemaDeclaresDocIdItActuallyRequests() {
        // Keeps the schema and the _source include list moving together in both directions:
        // declaring doc_id while getResponseFields() does not ask Fess for it would document a
        // field this tool can never emit, and asking for it without declaring it would break
        // additionalProperties:false. SearchToolTest pins the getResponseFields() half.
        @SuppressWarnings("unchecked")
        final Map<String, Object> hit =
                (Map<String, Object>) ((Map<String, Object>) new SearchTool().getOutputSchema().get("properties")).get("hits");
        @SuppressWarnings("unchecked")
        final Map<String, Object> hitItems = (Map<String, Object>) hit.get("items");
        @SuppressWarnings("unchecked")
        final Map<String, Object> hitProperties = (Map<String, Object>) hitItems.get("properties");
        assertTrue(hitProperties.containsKey("doc_id"), "search requests doc_id via getResponseFields(), so it must declare it");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSearchResultConformsToItsOwnSchema_MinimalHit() {
        // A hit carrying only title and url (no score/content_description) must still validate.
        final Map<String, Object> result = searchToolReturning(List.of(Map.of("title", "t", "url", "https://example.com/")))
                .call(Map.of("q", "x"), new McpCallContext());

        assertTrue(result.containsKey("content"), "the text block is kept for compatibility");
        assertTrue(result.containsKey("structuredContent"));
        assertConforms(new SearchTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");

        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) structured.get("hits");
        assertEquals(1, hits.size());
        assertFalse(hits.get(0).containsValue(null), "nulls must be stripped before serialization");
        assertEquals(Map.of("title", "t", "url", "https://example.com/"), hits.get(0),
                "no additional properties beyond the two present fields");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSearchResultOmitsGenuinelyAbsentTitleAndUrl() {
        // A raw Fess document item can lack the title/url keys entirely (not merely carry a
        // null or blank value) -- e.g. a malformed or partially-indexed document. The schema
        // does not require them, so buildHit() must omit the keys rather than fabricate "".
        final Map<String, Object> doc = new HashMap<>();
        doc.put("score", 3.0f);
        // Deliberately no "title" or "url" key at all.

        final Map<String, Object> result = searchToolReturning(List.of(doc)).call(Map.of("q", "x"), new McpCallContext());

        assertConforms(new SearchTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final Map<String, Object> hit = ((List<Map<String, Object>>) structured.get("hits")).get(0);
        assertFalse(hit.containsKey("title"), "an absent title must not be fabricated as \"\"");
        assertFalse(hit.containsKey("url"), "an absent url must not be fabricated as \"\"");
        assertEquals(Map.of("score", 3.0f), hit, "only the field Fess actually provided is present");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSearchResultConformsToItsOwnSchema_EveryOptionalFieldPresent() {
        final Map<String, Object> result = new ContainerFreeSearchTool() {
            @Override
            protected List<Map<String, Object>> executeSearch(final Map<String, Object> arguments, final SearchRenderData data) {
                final Map<String, Object> doc = new HashMap<>();
                doc.put("title", "Full Doc");
                doc.put("url", "https://example.com/full");
                doc.put("score", 12.5f);
                doc.put("content_description", "a <em>highlighted</em> snippet");
                doc.put("content", "raw content that must not leak into structuredContent");
                return List.of(doc);
            }
        }.call(Map.of("q", "x"), new McpCallContext());

        assertConforms(new SearchTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final Map<String, Object> hit = ((List<Map<String, Object>>) structured.get("hits")).get(0);
        assertEquals("Full Doc", hit.get("title"));
        assertEquals("https://example.com/full", hit.get("url"));
        assertEquals(12.5f, hit.get("score"));
        assertEquals("a highlighted snippet", hit.get("content_description"),
                "highlight markup is presentation and is stripped from the machine-readable channel too");
        assertFalse(hit.containsKey("content"), "raw content must not leak into structuredContent as its own key");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSearchResultStripsExplicitNullOptionalFields() {
        // A doc map can carry a key mapped to an explicit null (not merely absent).
        final Map<String, Object> doc = new HashMap<>();
        doc.put("title", "t");
        doc.put("url", "https://example.com/");
        doc.put("score", null);
        doc.put("content_description", null);

        final Map<String, Object> result = searchToolReturning(List.of(doc)).call(Map.of("q", "x"), new McpCallContext());

        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final Map<String, Object> hit = ((List<Map<String, Object>>) structured.get("hits")).get(0);
        assertFalse(hit.containsKey("score"), "explicit null score must be stripped, not copied");
        assertFalse(hit.containsKey("content_description"), "explicit null content_description must be stripped, not copied");
        assertConforms(new SearchTool().getOutputSchema(), structured, "structuredContent");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSearchResultConformsToItsOwnSchema_ZeroHits() {
        final Map<String, Object> result = new ContainerFreeSearchTool() {
            @Override
            protected List<Map<String, Object>> executeSearch(final Map<String, Object> arguments, final SearchRenderData data) {
                return List.of();
            }
        }.call(Map.of("q", "x"), new McpCallContext());

        assertConforms(new SearchTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
        assertEquals(List.of(), ((Map<String, Object>) result.get("structuredContent")).get("hits"));
    }

    @Test
    public void testSearchResultKeepsTheMarkdownTextBlockUnchanged() {
        // The deliberate deviation from the SHOULD: no serialized-JSON text block is added:
        // the Markdown block from createDocumentContent is preserved as-is.
        final Map<String, Object> result = searchToolReturning(List.of(Map.of("title", "t", "url", "https://example.com/", "score", 1.0f)))
                .call(Map.of("q", "x"), new McpCallContext());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        final String text = (String) content.get(0).get("text");
        assertTrue(text.contains("**Title**: t"), "text block must remain Markdown-ish, not JSON");
        assertTrue(text.startsWith("**Title**:"), "text block must not be replaced by serialized JSON");
    }

    // ------------------------------------------------------------------
    // suggest
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testSuggestOutputSchemaRequiresOnlyText() {
        final Map<String, Object> schema = new SuggestTool().getOutputSchema();
        final Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("suggestions"));
        final Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>) properties.get("suggestions")).get("items");
        assertEquals(List.of("text"), item.get("required"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSuggestResultConformsToItsOwnSchema() {
        final Map<String, Object> result = new SuggestTool() {
            @Override
            protected List<String> executeSuggest(final String query, final Object numArg) {
                return List.of("fess", "fess crawler");
            }
        }.call(Map.of("q", "fes"), new McpCallContext());

        assertTrue(result.containsKey("content"), "the text block is kept for compatibility");
        assertConforms(new SuggestTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final List<Map<String, Object>> suggestions = (List<Map<String, Object>>) structured.get("suggestions");
        assertEquals(List.of(Map.of("text", "fess"), Map.of("text", "fess crawler")), suggestions);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSuggestResultConformsToItsOwnSchema_NoSuggestions() {
        final Map<String, Object> result = new SuggestTool() {
            @Override
            protected List<String> executeSuggest(final String query, final Object numArg) {
                return List.of();
            }
        }.call(Map.of("q", "zzz"), new McpCallContext());

        assertConforms(new SuggestTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
        assertEquals(List.of(), ((Map<String, Object>) result.get("structuredContent")).get("suggestions"));
        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertTrue(((String) content.get(0).get("text")).startsWith("No suggestions found"),
                "the text block's not-found message is kept for compatibility");
    }

    // ------------------------------------------------------------------
    // get_document
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testGetDocumentOutputSchemaRequiresEveryFieldItAlwaysProduces() {
        // Unlike search/suggest, every field get_document emits is unconditionally present
        // (each falls back to "" rather than being omitted), so all of them are required --
        // including truncated/content_length, which call() defaults rather than leaving null.
        final Map<String, Object> schema = new GetDocumentTool().getOutputSchema();
        assertEquals(List.of("doc_id", "title", "url", "content", "truncated", "content_length"), schema.get("required"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetDocumentResultConformsToItsOwnSchema() {
        final Map<String, Object> result = new GetDocumentTool() {
            @Override
            protected Map<String, Object> executeGetDocument(final String docId) {
                return Map.of("title", "t", "url", "https://example.com/", "content", "body");
            }
        }.call(Map.of("doc_id", "doc1"), new McpCallContext());

        assertTrue(result.containsKey("content"), "the text block is kept for compatibility");
        assertConforms(new GetDocumentTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        assertEquals("doc1", structured.get("doc_id"));
        assertEquals("t", structured.get("title"));
        assertEquals("https://example.com/", structured.get("url"));
        assertEquals("body", structured.get("content"));
    }

    @Test
    public void testGetDocumentResultConformsToItsOwnSchema_EmptyContentStillValidates() {
        // The minimal case for get_document: an indexed-but-blank document. Every field is
        // still present (as "", the same fallback createDocumentContent's sibling uses), so
        // this must still validate against the all-four-required schema.
        final Map<String, Object> result = new GetDocumentTool() {
            @Override
            protected Map<String, Object> executeGetDocument(final String docId) {
                return Map.of("title", "", "url", "", "content", "");
            }
        }.call(Map.of("doc_id", "doc1"), new McpCallContext());

        assertConforms(new GetDocumentTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
    }

    @Test
    public void testGetDocumentNotFoundHasNoStructuredContent() {
        // isError:true results don't claim outputSchema conformance; only found-document
        // results do.
        final Map<String, Object> result = new GetDocumentTool() {
            @Override
            protected Map<String, Object> executeGetDocument(final String docId) {
                return null;
            }
        }.call(Map.of("doc_id", "missing"), new McpCallContext());

        assertEquals(Boolean.TRUE, result.get("isError"));
        assertFalse(result.containsKey("structuredContent"), "an error result must not claim to conform to the output schema");
    }

    // ------------------------------------------------------------------
    // get_index_stats
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void testIndexStatsOutputSchemaOnlyRequiresAlwaysSetFields() {
        final Map<String, Object> schema = new IndexStatsTool().getOutputSchema();
        final Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        final Map<String, Object> index = (Map<String, Object>) properties.get("index");
        final List<String> indexRequired = (List<String>) index.get("required");
        assertFalse(indexRequired.contains("index_name"), "index_name is not set when fessConfig itself throws");
        assertFalse(indexRequired.contains("error"), "error is only set when the stats lookup fails");
        assertTrue(indexRequired.contains("document_count"), "document_count is set on every branch of collectIndexStats()");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testIndexStatsResultConformsToItsOwnSchema() {
        final Map<String, Object> result = new IndexStatsTool() {
            @Override
            public Map<String, Object> collectIndexStats() {
                final Map<String, Object> index = new HashMap<>();
                index.put("index_name", "fess.search");
                index.put("document_count", 42L);

                final Map<String, Object> config = new HashMap<>();
                config.put("max_page_size", 20);

                final Map<String, Object> memory = new HashMap<>();
                memory.put("total_bytes", 1000L);
                memory.put("free_bytes", 500L);
                memory.put("used_bytes", 500L);
                memory.put("max_bytes", 2000L);
                final Map<String, Object> system = new HashMap<>();
                system.put("memory", memory);

                final Map<String, Object> stats = new HashMap<>();
                stats.put("index", index);
                stats.put("config", config);
                stats.put("system", system);
                return stats;
            }
        }.call(Map.of(), new McpCallContext());

        assertTrue(result.containsKey("content"), "IndexStatsTool already put the JSON in the text block (SHOULD-compliant)");
        assertConforms(new IndexStatsTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testIndexStatsResultConformsToItsOwnSchema_MinimalStatsWithNullError() {
        // The failure branch of collectIndexStats(): index_name is never set, and error can be
        // null (an exception with no message).
        final Map<String, Object> result = new IndexStatsTool() {
            @Override
            public Map<String, Object> collectIndexStats() {
                final Map<String, Object> index = new HashMap<>();
                index.put("document_count", -1);
                index.put("error", null);

                final Map<String, Object> config = new HashMap<>();
                config.put("max_page_size", 20);

                final Map<String, Object> memory = new HashMap<>();
                memory.put("total_bytes", 1000L);
                memory.put("free_bytes", 500L);
                memory.put("used_bytes", 500L);
                memory.put("max_bytes", 2000L);
                final Map<String, Object> system = new HashMap<>();
                system.put("memory", memory);

                final Map<String, Object> stats = new HashMap<>();
                stats.put("index", index);
                stats.put("config", config);
                stats.put("system", system);
                return stats;
            }
        }.call(Map.of(), new McpCallContext());

        assertConforms(new IndexStatsTool().getOutputSchema(), result.get("structuredContent"), "structuredContent");
        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final Map<String, Object> index = (Map<String, Object>) structured.get("index");
        assertFalse(index.containsKey("error"), "a null error must be stripped, not copied as null");
        assertFalse(index.containsKey("index_name"), "index_name genuinely absent on the failure branch");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testIndexStatsStructuredContentCarriesTheSameDataAsTheTextBlock() {
        // "put the same content in structuredContent rather than re-deriving it": the text
        // block already carries the serialized JSON of collectIndexStats(); structuredContent
        // must be built from that same map, not some other computation.
        final Map<String, Object> result = new IndexStatsTool() {
            @Override
            public Map<String, Object> collectIndexStats() {
                final Map<String, Object> index = new HashMap<>();
                index.put("document_count", 7L);
                final Map<String, Object> config = new HashMap<>();
                config.put("max_page_size", 20);
                final Map<String, Object> memory = new HashMap<>();
                memory.put("total_bytes", 1L);
                memory.put("free_bytes", 1L);
                memory.put("used_bytes", 1L);
                memory.put("max_bytes", 1L);
                final Map<String, Object> system = new HashMap<>();
                system.put("memory", memory);
                final Map<String, Object> stats = new HashMap<>();
                stats.put("index", index);
                stats.put("config", config);
                stats.put("system", system);
                return stats;
            }
        }.call(Map.of(), new McpCallContext());

        final List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        final String jsonText = (String) content.get(0).get("text");
        assertTrue(jsonText.contains("\"document_count\":7"), "text block carries the raw stats as JSON");

        final Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        final Map<String, Object> index = (Map<String, Object>) structured.get("index");
        assertEquals(7L, index.get("document_count"), "structuredContent must carry the same document_count as the text block");
    }

    // ------------------------------------------------------------------
    // Cross-tool: no tool still returns the old placeholder schema.
    // ------------------------------------------------------------------

    @Test
    public void testNoToolReturnsThePlaceholderOutputSchema() {
        final Map<String, Object> placeholder = Map.of("type", "object");
        for (final McpTool tool : List.<McpTool> of(new SearchTool(), new SuggestTool(), new GetDocumentTool(), new IndexStatsTool())) {
            assertFalse(placeholder.equals(tool.getOutputSchema()), tool.getName() + " must declare a real schema, not the placeholder");
        }
    }

    /**
     * A {@link SearchTool} that can run without a DI container.
     * <p>
     * {@code call()} asks Fess whether result collapsing is on, which goes through
     * {@code ComponentUtil.getFessConfig()}. Tests that only care about the shape of a result
     * extend this instead of {@link SearchTool} directly, so that one container dependency does
     * not have to be restated at every call site.
     * </p>
     */
    private abstract static class ContainerFreeSearchTool extends SearchTool {
        @Override
        protected boolean isResultCollapsed() {
            return false;
        }
    }
}
