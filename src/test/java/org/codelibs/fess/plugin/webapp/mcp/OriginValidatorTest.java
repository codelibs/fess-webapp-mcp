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
package org.codelibs.fess.plugin.webapp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.codelibs.fess.plugin.webapp.api.mcp.McpHttpTestSupport;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OriginValidator}.
 */
public class OriginValidatorTest {

    @Test
    public void testAbsentOriginIsAllowed() {
        // Not a browser request. The spec only mandates 403 for a *present* invalid Origin.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        OriginValidator.validate(request, Set.of());
    }

    @Test
    public void testForeignOriginIsRejectedWith403() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "https://evil.example.com");
        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testSameOriginIsAllowed() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "https://fess.example.com");
        OriginValidator.validate(request, Set.of());
    }

    @Test
    public void testConfiguredOriginIsAllowed() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "https://client.example.com");
        OriginValidator.validate(request, Set.of("https://client.example.com"));
    }

    @Test
    public void testDefaultHttpsPortIsEquivalentToNoPort() {
        // Browsers omit the default port when sending Origin. The self-origin comparison must
        // treat "https + serverPort 443" as equal to an Origin with no explicit port. The Origin
        // header below deliberately omits ":443" -- if it carried the port explicitly, the self
        // origin's own explicit 443 would still match it even without the "omit the scheme
        // default" rule, and the test would not actually exercise that rule.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "https://fess.example.com");
        OriginValidator.validate(request, Set.of());
    }

    @Test
    public void testDefaultHttpPortIsEquivalentToNoPort() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("fess.example.com");
        request.setServerPort(80);
        request.addHeader("Origin", "http://fess.example.com");
        OriginValidator.validate(request, Set.of());
    }

    @Test
    public void testNonDefaultPortMustMatchExactly() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(8443);
        request.addHeader("Origin", "https://fess.example.com");
        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testLiteralNullOriginIsRejectedWith403() {
        // Browsers send the literal string "null" (not an absent header) as Origin for opaque
        // origins, e.g. a sandboxed iframe or a redirected/data: navigation. It must not parse
        // as a scheme://host origin, so it is present-and-invalid, not absent.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "null");
        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testEmptyOriginIsRejectedWith403() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "");
        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testOriginWithoutAuthorityIsRejectedWith403() {
        // "http://" has a scheme but no host; java.net.URI throws URISyntaxException for it.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "http://");
        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testOriginPathIsIgnoredForMatching() {
        // A conformant browser never sends a path on Origin; this documents that if one is
        // present anyway, only scheme/host/port are compared -- see OriginValidator#validate.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "https://fess.example.com/some/path");
        OriginValidator.validate(request, Set.of());
    }

    @Test
    public void testSuffixSpoofedOriginIsRejectedWith403() {
        // "fess.example.com.evil.com" ends with the trusted host as a substring; exact equality
        // must reject it rather than an endsWith-style check that would accept it.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "https://fess.example.com.evil.com");
        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testUppercaseSchemeAndHostMatchLowercaseSelf() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "HTTPS://FESS.EXAMPLE.COM");
        OriginValidator.validate(request, Set.of());
    }
}
