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

import java.util.List;
import java.util.Map;

import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link GetDocumentTool}.
 *
 * <p>
 * {@code invokeGetDocument} moved out of {@code McpApiManager} into this class; the missing-doc_id
 * behaviour it must preserve is already covered end-to-end by
 * {@code McpApiManagerTest#testHandleInvoke_GetDocument_MissingDocId}, and is exercised again
 * here directly against the tool. {@code testInputSchemaRequiresDocId} migrated similarly from
 * {@code testHandleListTools_HasGetDocumentTool}.
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
    public void testCall_RequiresDIContainer() {
        // Past the missing-doc_id check, call() reaches ComponentUtil.getFessConfig() and throws.
        try {
            getDocumentTool.call(Map.of("doc_id", "doc1"), new McpCallContext());
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }
}
