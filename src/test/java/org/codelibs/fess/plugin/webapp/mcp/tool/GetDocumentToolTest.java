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
 * Test class for {@link GetDocumentTool}.
 *
 * <p>
 * {@code invokeGetDocument} moved out of {@code McpApiManager} into this class; the missing-doc_id
 * behaviour it must preserve was formerly covered end-to-end by the retired
 * {@code McpApiManagerTest#testHandleInvoke_GetDocument_MissingDocId} and is exercised here
 * directly against the tool instead. {@code testInputSchemaRequiresDocId} migrated similarly from
 * the retired {@code testHandleListTools_HasGetDocumentTool}.
 * </p>
 */
public class GetDocumentToolTest {

    private final GetDocumentTool getDocumentTool = new GetDocumentTool();

    @Test
    @SuppressWarnings("unchecked")
    public void testInputSchemaRequiresDocId() {
        final Map<String, Object> schema = getDocumentTool.getInputSchema();
        final List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("doc_id"), "doc_id should be required");
    }

    @Test
    public void testCall_MissingDocId() {
        try {
            getDocumentTool.call(Map.of(), new McpCallContext());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    @Test
    public void testCall_EmptyDocId() {
        try {
            getDocumentTool.call(Map.of("doc_id", ""), new McpCallContext());
            fail("Should have thrown McpApiException");
        } catch (final McpApiException e) {
            assertEquals(ErrorCode.InvalidParams, e.getCode(), "Should be InvalidParams");
        }
    }

    @Test
    public void testCall_NonStringDocId_IsInvalidParams() {
        // getInputSchema() declares doc_id as a string and was applied nowhere, so this used to
        // reach the unchecked cast and surface as "class java.lang.Integer cannot be cast to
        // class java.lang.String ..." in an isError:true result, instead of -32602.
        final McpError error =
                assertThrows(McpError.class, () -> getDocumentTool.call(Map.of("doc_id", Integer.valueOf(1)), new McpCallContext()),
                        "a wrong-typed doc_id must be refused before the cast");
        assertEquals(ErrorCode.InvalidParams, error.getErrorCode(), "the MCP spec requires -32602 for an invalid argument");
        assertEquals(200, error.getHttpStatus(), "an application-level failure stays HTTP 200");
        assertTrue(error.getMessage().contains("parameter: doc_id"), "the message must name the argument: " + error.getMessage());
    }

    @Test
    public void testCall_RequiresDIContainer() {
        // Past the missing-doc_id check, call() reaches ComponentUtil.getFessConfig() and throws.
        try {
            getDocumentTool.call(Map.of("doc_id", "doc1"), new McpCallContext());
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testGetDocumentSaysWhenItTruncatedTheContent() {
        // get_document is the "give me the whole document" primitive, and it silently returned
        // mcp.content.max.length characters plus an ellipsis. Measured against a live server: a
        // 20,957-character document came back as 10,003 characters with nothing in the response
        // to say so, and the trailing "..." is indistinguishable from a document that genuinely
        // ends in one. A client asked to summarise such a document summarises half of it.
        final GetDocumentTool tool = newToolWithContentMaxLength(10);

        final Map<String, Object> fields = tool.buildContentFields("0123456789abcdef");

        assertEquals("0123456789...", fields.get("content"), "content is still truncated at the configured length");
        assertEquals(Boolean.TRUE, fields.get("truncated"), "the caller must be told the content is incomplete");
        assertEquals(16, fields.get("content_length"), "the untruncated length lets a client report what it is missing");
    }

    @Test
    public void testGetDocumentReportsUntruncatedContentAsComplete() {
        final GetDocumentTool tool = newToolWithContentMaxLength(10);

        final Map<String, Object> fields = tool.buildContentFields("short");

        assertEquals("short", fields.get("content"));
        assertEquals(Boolean.FALSE, fields.get("truncated"), "a complete document must not be flagged as truncated");
        assertEquals(5, fields.get("content_length"));
    }

    @Test
    public void testContentExactlyAtTheLimitIsNotTruncated() {
        // Boundary: DocumentFormatter cuts only when length > max, so length == max is complete.
        // Without this a >= mutant would survive and every full-length document would claim to
        // be truncated.
        final GetDocumentTool tool = newToolWithContentMaxLength(10);

        final Map<String, Object> fields = tool.buildContentFields("0123456789");

        assertEquals("0123456789", fields.get("content"));
        assertEquals(Boolean.FALSE, fields.get("truncated"), "content exactly at the limit is complete");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testOutputSchemaDeclaresAndRequiresTruncated() {
        final Map<String, Object> schema = getDocumentTool.getOutputSchema();
        final Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        final List<String> required = (List<String>) schema.get("required");

        assertTrue(properties.containsKey("truncated"), "outputSchema must declare truncated");
        assertTrue(properties.containsKey("content_length"), "outputSchema must declare content_length");
        assertTrue(required.containsAll(List.of("truncated", "content_length")),
                "both are computed for every found document, so both are required: " + required);
    }

    private static GetDocumentTool newToolWithContentMaxLength(final int maxLength) {
        return new GetDocumentTool() {
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
}
