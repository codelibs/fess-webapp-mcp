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

import org.codelibs.fess.util.ComponentUtil;

/**
 * Content-formatting helpers shared by {@link SearchTool} and {@link GetDocumentTool}.
 *
 * <p>
 * Both tools truncate document content to the same configurable maximum length; this class is
 * the single place that decides that length and applies it, so the two tools cannot drift apart
 * the way {@code McpApiManager}'s schema and behaviour once did.
 * </p>
 */
public class DocumentFormatter {

    /** The default maximum content length, used when {@code mcp.content.max.length} is unset. */
    protected static final int DEFAULT_CONTENT_MAX_LENGTH = 10000;

    /**
     * Creates a document formatter.
     */
    public DocumentFormatter() {
        // nothing to initialize
    }

    /**
     * Truncates content to the specified maximum length.
     *
     * @param content the content to truncate
     * @param maxLength the maximum length
     * @return the truncated content, suffixed with {@code "..."} when truncation occurred; the
     *         original content (including {@code null}) when it was already short enough
     */
    public String truncateContent(final String content, final int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    /**
     * Returns the maximum content length from system property.
     *
     * @return the maximum content length
     */
    protected int getContentMaxLength() {
        return ComponentUtil.getFessConfig().getSystemPropertyAsInt("mcp.content.max.length", DEFAULT_CONTENT_MAX_LENGTH);
    }
}
