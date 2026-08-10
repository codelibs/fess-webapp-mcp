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
package org.codelibs.fess.plugin.webapp.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP-boundary tests for McpApiManager. These run without a DI container,
 * so every config read and the body read must go through a protected seam.
 */
public class McpApiManagerHttpTest {

    /** Test double: supplies a canned body and never touches ComponentUtil. */
    static class TestManager extends McpApiManager {
        String body = "";

        @Override
        protected String readRequestBody(final HttpServletRequest request) throws IOException {
            return body;
        }

        @Override
        protected void writeHeaders(final HttpServletResponse response) {
            // no-op: the real implementation reads api.json.response.headers from the container
        }
    }

    @Test
    public void testGetIsRejectedWith405() throws Exception {
        final TestManager manager = new TestManager();
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        assertEquals(405, response.getStatus(), "GET must be rejected");
        assertEquals("POST", response.getHeader("Allow"), "Allow header must advertise POST");
        assertEquals("", McpHttpTestSupport.bodyOf(response), "405 returns before writing a body");
        // No runtime assertion here can detect a regression to response.sendError(): on this
        // mocklet, sendError(int) just delegates to setStatus(int) and sendRedirect(String) is a
        // complete no-op, so a sendError()-based 405 would report the exact same status and the
        // exact same absent Location header as the correct setStatus()-based implementation. That
        // regression is guarded instead by SendErrorProhibitedTest, which scans the production
        // sources for any use of response.sendError(...).
    }
}
