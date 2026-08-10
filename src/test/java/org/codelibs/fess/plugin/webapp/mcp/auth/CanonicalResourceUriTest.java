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
package org.codelibs.fess.plugin.webapp.mcp.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.codelibs.fess.plugin.webapp.api.mcp.McpHttpTestSupport;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.junit.jupiter.api.Test;

/**
 * Focused, container-free tests for {@link CanonicalResourceUri}: the single derivation shared
 * by OAuth audience validation, the PRM {@code resource} field, and the {@code
 * resource_metadata} challenge URL.
 */
public class CanonicalResourceUriTest {

    @Test
    public void testDerivedFromRequestStripsDefaultHttpsPort() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);

        assertEquals("https://fess.example.com/mcp", CanonicalResourceUri.resolve(request, "", Set.of()));
    }

    @Test
    public void testDerivedFromRequestKeepsNonDefaultPort() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("fess.internal");
        request.setServerPort(8080);

        assertEquals("http://fess.internal:8080/mcp", CanonicalResourceUri.resolve(request, "", Set.of()));
    }

    @Test
    public void testAudienceIsIndependentOfRequestPath() {
        final MockletHttpServletRequestImpl root = McpHttpTestSupport.newRequest("POST", "/mcp");
        root.setScheme("https");
        root.setServerName("fess.example.com");
        root.setServerPort(443);
        final MockletHttpServletRequestImpl sub = McpHttpTestSupport.newRequest("POST", "/mcp/x");
        sub.setScheme("https");
        sub.setServerName("fess.example.com");
        sub.setServerPort(443);

        assertEquals(CanonicalResourceUri.resolve(root, "", Set.of()), CanonicalResourceUri.resolve(sub, "", Set.of()),
                "a token bound to /mcp must not fail at /mcp/x");
    }

    @Test
    public void testConfiguredAudienceTakesPriorityOverRequest() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);

        assertEquals("https://public.example.com/mcp", CanonicalResourceUri.resolve(request, "https://public.example.com/mcp", Set.of()));
    }

    @Test
    public void testConfiguredAudienceStripsFragmentAndTrailingSlash() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        assertEquals("https://public.example.com/mcp",
                CanonicalResourceUri.resolve(request, "https://public.example.com/mcp/#fragment", Set.of()));
    }

    @Test
    public void testUntrustedRemoteAddrIgnoresForwardedHeaders() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(80);
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "attacker.example.com");

        assertEquals("http://internal-host/mcp", CanonicalResourceUri.resolve(request, "", Set.of("10.0.0.1")),
                "an untrusted caller's X-Forwarded-Host must not change the audience");
    }

    @Test
    public void testTrustedProxyForwardedHeadersAreHonoured() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "public.example.com");

        assertEquals("https://public.example.com/mcp", CanonicalResourceUri.resolve(request, "", Set.of("10.0.0.1")));
    }

    @Test
    public void testTrustedProxyForwardedHostCarriesExplicitPort() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "public.example.com:9443");

        assertEquals("https://public.example.com:9443/mcp", CanonicalResourceUri.resolve(request, "", Set.of("10.0.0.1")));
    }

    @Test
    public void testTrustedProxySeparateForwardedPortHeader() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "public.example.com");
        request.addHeader("X-Forwarded-Port", "9443");

        assertEquals("https://public.example.com:9443/mcp", CanonicalResourceUri.resolve(request, "", Set.of("10.0.0.1")));
    }

    @Test
    public void testMetadataUrlStripsMcpSuffixAndAppendsWellKnownPath() {
        assertEquals("https://fess.example.com/.well-known/oauth-protected-resource/mcp",
                CanonicalResourceUri.metadataUrl("https://fess.example.com/mcp"));
    }

    @Test
    public void testMetadataUrlHandlesConfiguredAudienceWithoutMcpSuffix() {
        assertEquals("urn:fess-resource/.well-known/oauth-protected-resource/mcp", CanonicalResourceUri.metadataUrl("urn:fess-resource"));
    }
}
