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
package org.codelibs.fess.plugin.webapp.mcp.json;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.codelibs.fess.plugin.webapp.mcp.ErrorCode;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;

/**
 * JSON parsing and serialization for the MCP endpoint, backed by OpenSearch XContent.
 */
public final class Json {

    private Json() {
        // no instantiation
    }

    /**
     * Parses a JSON object.
     *
     * <p>A JSON array body (e.g. a batch request) is deliberately rejected: {@link XContentParser#map()}
     * does not throw for array input, it silently returns an empty map, so the array shape is checked
     * explicitly before delegating to it.</p>
     *
     * @param body the raw request body
     * @return the decoded object
     * @throws McpError with HTTP 400 and -32700 when the body is empty, malformed, or not a JSON object
     */
    public static Map<String, Object> parseObject(final String body) {
        if (body == null || body.isBlank()) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.ParseError, "empty request body");
        }
        try (XContentParser parser =
                JsonXContent.jsonXContent.createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, body)) {
            if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.ParseError, "request body must be a JSON object");
            }
            return parser.map();
        } catch (final McpError e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new McpError(HttpServletResponse.SC_BAD_REQUEST, ErrorCode.ParseError, "malformed JSON: " + e.getMessage());
        }
    }

    /**
     * Serializes a map to compact JSON.
     *
     * @param value the object to serialize
     * @return the JSON text
     * @throws McpError with HTTP 500 and -32603 when serialization fails
     */
    public static String write(final Map<String, ?> value) {
        try {
            return JsonXContent.contentBuilder().map(value).toString();
        } catch (final IOException e) {
            throw new McpError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ErrorCode.InternalError,
                    "failed to serialize response: " + e.getMessage());
        }
    }
}
