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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * Test class for {@link DocumentFormatter}.
 *
 * <p>
 * Migrated from {@code McpApiManagerTest} ({@code testTruncateContent*},
 * {@code testGetContentMaxLength*}) when {@code truncateContent} and {@code getContentMaxLength}
 * moved out of {@code McpApiManager} into this shared formatting helper.
 * </p>
 */
public class DocumentFormatterTest {

    private final DocumentFormatter formatter = new DocumentFormatter();

    @Test
    public void testTruncateContent_Null() {
        assertNull(formatter.truncateContent(null, 100), "Null should return null");
    }

    @Test
    public void testTruncateContent_ShortContentNotTruncated() {
        final String shortContent = "Short";
        assertEquals(shortContent, formatter.truncateContent(shortContent, 100), "Short content should not be truncated");
    }

    @Test
    public void testTruncateContent_ExactLengthNotTruncated() {
        final String exactContent = "12345";
        assertEquals(exactContent, formatter.truncateContent(exactContent, 5), "Exact length content should not be truncated");
    }

    @Test
    public void testTruncateContent_LongContentTruncated() {
        final String longContent = "This is a long content that should be truncated";
        final String truncated = formatter.truncateContent(longContent, 10);
        assertEquals("This is a ...", truncated, "Truncated content should be 10 chars + ...");
    }

    @Test
    public void testTruncateContent_EmptyString() {
        assertEquals("", formatter.truncateContent("", 100), "Empty string should return empty");
    }

    @Test
    public void testTruncateContent_ZeroMaxLength() {
        final String result = formatter.truncateContent("test", 0);
        assertEquals("...", result, "Zero max length should return ...");
    }

    @Test
    public void testGetContentMaxLength_RequiresDIContainer() {
        // getContentMaxLength requires ComponentUtil which needs a DI container.
        try {
            formatter.getContentMaxLength();
            fail("Should fail due to DI container not initialized in unit test");
        } catch (final IllegalStateException e) {
            // Expected in unit test when DI container is not initialized
            assertTrue(e.getMessage().contains("container"), "Should fail due to container not initialized");
        }
    }

    @Test
    public void testGetContentMaxLength_Overridable() {
        final DocumentFormatter custom = new DocumentFormatter() {
            @Override
            protected int getContentMaxLength() {
                return 42;
            }
        };
        assertEquals(42, custom.getContentMaxLength(), "Subclasses must be able to override the configured max length");
    }

    @Test
    public void testContentMaxLengthConfigKeyAndDefaultArePinned() {
        // getContentMaxLength()'s real body reads ComponentUtil, so every other test overrides
        // it wholesale and neither of these literals was covered. Shrinking the default to a
        // small number truncates the content of every indexed document handed to the model,
        // and a typo'd key makes the operator's mcp.content.max.length setting inert -- both
        // silently, and both with the rest of the suite still green.
        assertEquals("mcp.content.max.length", DocumentFormatter.CONTENT_MAX_LENGTH_PROPERTY);
        assertEquals(10000, DocumentFormatter.DEFAULT_CONTENT_MAX_LENGTH);
    }
}
