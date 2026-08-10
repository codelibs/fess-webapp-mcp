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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.helper.SearchHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.exception.McpApiException;
import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.optional.OptionalThing;

/**
 * The {@code get_document} MCP tool: retrieve a single Fess document by its document ID.
 */
public class GetDocumentTool implements McpTool {

    private static final Logger logger = LogManager.getLogger(GetDocumentTool.class);

    /**
     * Creates a {@code get_document} tool.
     */
    public GetDocumentTool() {
        // nothing to initialize
    }

    @Override
    public String getName() {
        return "get_document";
    }

    @Override
    public String getDescription() {
        return "Retrieve a document by its document ID";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("doc_id", Map.of("type", "string", "description", "document ID to retrieve"));

        final Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("doc_id"));
        return schema;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "object");
    }

    @Override
    public Map<String, Object> getAnnotations() {
        return Map.of("title", "Get Document", "readOnlyHint", true, "destructiveHint", false, "openWorldHint", false);
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Collections.emptySet();
    }

    @Override
    public Map<String, Object> call(final Map<String, Object> arguments, final McpCallContext context) {
        final String docId = (String) arguments.get("doc_id");
        if (docId == null || docId.isEmpty()) {
            throw new McpApiException(ErrorCode.InvalidParams, "Missing required parameter: doc_id");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[MCP] Retrieving document: doc_id={}", docId);
        }

        final FessConfig fessConfig = getFessConfig();
        final String[] fields = new String[] { fessConfig.getIndexFieldTitle(), fessConfig.getIndexFieldContent(),
                fessConfig.getIndexFieldUrl(), fessConfig.getIndexFieldDocId(), fessConfig.getIndexFieldLastModified() };

        return getSearchHelper().getDocumentByDocId(docId, fields, OptionalThing.empty()).map(doc -> {
            final String title = String.valueOf(doc.getOrDefault(fessConfig.getIndexFieldTitle(), ""));
            final String url = String.valueOf(doc.getOrDefault(fessConfig.getIndexFieldUrl(), ""));
            final String content = String.valueOf(doc.getOrDefault(fessConfig.getIndexFieldContent(), ""));
            final DocumentFormatter formatter = getDocumentFormatter();
            final String displayContent = formatter.truncateContent(content, formatter.getContentMaxLength());

            final StringBuilder sb = new StringBuilder();
            sb.append("**Title**: ").append(title).append("\n");
            sb.append("**URL**: ").append(url).append("\n");
            sb.append("**Doc ID**: ").append(docId).append("\n\n");
            sb.append(displayContent);

            return Map.<String, Object> of("content", List.of(Map.of("type", "text", "text", sb.toString())));
        }).orElseGet(() -> {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", "Document not found: " + docId)));
            result.put("isError", true);
            return result;
        });
    }

    /**
     * Returns the Fess configuration component.
     *
     * @return the Fess configuration
     */
    protected FessConfig getFessConfig() {
        return ComponentUtil.getFessConfig();
    }

    /**
     * Returns the search helper component used to look up the document.
     *
     * @return the search helper
     */
    protected SearchHelper getSearchHelper() {
        return ComponentUtil.getSearchHelper();
    }

    /**
     * Returns the {@link DocumentFormatter} used to truncate document content.
     *
     * @return a document formatter
     */
    protected DocumentFormatter getDocumentFormatter() {
        return new DocumentFormatter();
    }
}
