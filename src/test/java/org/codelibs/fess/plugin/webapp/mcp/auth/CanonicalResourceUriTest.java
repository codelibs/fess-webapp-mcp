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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    public void testTrustedProxyForwardedHostWithEmbeddedCrlfIsRejected() {
        // Even from a TRUSTED proxy, a forwarded header value carrying an embedded CR/LF must
        // never reach the WWW-Authenticate response header this value eventually feeds into
        // (via resource_metadata). Falls back to the servlet-observed origin instead of
        // propagating the injected value.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(80);
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "attacker.example.com\r\nX-Injected: evil");

        assertEquals("http://internal-host/mcp", CanonicalResourceUri.resolve(request, "", Set.of("10.0.0.1")),
                "an embedded CR/LF in a forwarded header must not reach the canonical URI, even from a trusted proxy");
    }

    @Test
    public void testTrustedProxyForwardedProtoWithEmbeddedCrlfIsRejected() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(80);
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https\r\nX-Injected: evil");
        request.addHeader("X-Forwarded-Host", "public.example.com");

        assertEquals("http://internal-host/mcp", CanonicalResourceUri.resolve(request, "", Set.of("10.0.0.1")));
    }

    @Test
    public void testMetadataUrlStripsMcpSuffixAndAppendsWellKnownPath() {
        assertEquals("https://fess.example.com/.well-known/oauth-protected-resource/mcp",
                CanonicalResourceUri.metadataUrl("https://fess.example.com/mcp"));
    }

    @Test
    public void testMetadataUrlKeepsAContextPathPrefix() {
        // A Fess deployed under a non-root context path (FESS_CONTEXT_PATH / -Dfess.context.path),
        // or behind a proxy mounting it at a subpath, legitimately identifies as
        // https://host/api/mcp -- so the well-known URL must be built under that same /api prefix.
        //
        // This is a DELIBERATE deviation from RFC 9728 §3.1, which would host-root the well-known
        // path as https://fess.example.com/.well-known/oauth-protected-resource/api/mcp. That URL
        // lies above the servlet context and Fess cannot serve it at all under a context path,
        // whereas the prefix-preserving form below IS served: McpMetadataApiManager#matches()
        // compares getServletPath(), which excludes the context path, so this URL arrives there as
        // the literal /.well-known/oauth-protected-resource/mcp it matches on. The two forms
        // coincide at the root context, so the deviation is visible only to subpath deployments.
        assertEquals("https://fess.example.com/api/.well-known/oauth-protected-resource/mcp",
                CanonicalResourceUri.metadataUrl("https://fess.example.com/api/mcp"));
    }

    @Test
    public void testMetadataUrlOnAnUncompatibleAudienceIsNotAContractThisTestPinsAsCorrect() {
        // metadataUrl() itself is a pure string transform with no validation of its own -- for an
        // audience whose path is not /mcp, it produces a URL nothing serves (a real bug the
        // reviewer of an earlier round of this task caught). This is no longer reachable through
        // isUsable()/McpMetadataApiManager, which now both refuse such an audience via
        // isCompatibleAudience below, before metadataUrl() would ever see it. This test exists
        // only to document that metadataUrl() itself still has no such guard -- it does NOT
        // assert the malformed output is fine to keep producing.
        final String malformed = CanonicalResourceUri.metadataUrl("urn:fess-resource");
        assertTrue(malformed.startsWith("urn:fess-resource"),
                "metadataUrl() does not itself validate its input -- callers must check isCompatibleAudience first");
    }

    // ------------------------------------------------------------------
    // isCompatibleAudience: the guard that keeps metadataUrl()'s degenerate case unreachable.
    // ------------------------------------------------------------------

    @Test
    public void testCompatibleAudienceAcceptsBlank() {
        // Blank means "derive from the request", which always ends in /mcp by construction.
        assertTrue(CanonicalResourceUri.isCompatibleAudience(""));
        assertTrue(CanonicalResourceUri.isCompatibleAudience(null));
    }

    @Test
    public void testCompatibleAudienceAcceptsAnMcpSuffixedValue() {
        assertTrue(CanonicalResourceUri.isCompatibleAudience("https://fess.example.com/mcp"));
        // DO NOT "fix" this to assertFalse. It has now been reported twice as a bug that an
        // audience with a leading path segment is accepted; it is correct, and tightening it would
        // be a security regression, not a hardening:
        //
        //   * /api/mcp is the CORRECT RFC 8707 resource identifier for a Fess running under the
        //     context path /api (FESS_CONTEXT_PATH / -Dfess.context.path), and metadataUrl() maps
        //     it to a URL McpMetadataApiManager really does serve -- see
        //     testMetadataUrlKeepsAContextPathPrefix above, which pins exactly that.
        //   * "Unusable" is not a loud failure here. isCompatibleAudience feeds
        //     isUsableConfiguration, which feeds OAuthResourceServerAuthenticator#isUsable(),
        //     which on false makes McpApiManager#getAuthenticator fall back to NoneAuthenticator
        //     -- i.e. /mcp silently served ANONYMOUSLY to a deployment that had configured OAuth
        //     correctly.
        //
        // The contract is "must end in /mcp", stated identically in README's mcp.oauth.audience
        // entries and in McpApiManager's startup ERROR string.
        assertTrue(CanonicalResourceUri.isCompatibleAudience("https://fess.example.com/api/mcp"),
                "a context-path deployment's audience must stay usable -- refusing it falls back to anonymous access");
    }

    @Test
    public void testCompatibleAudienceToleratesTrailingSlashAndFragment() {
        assertTrue(CanonicalResourceUri.isCompatibleAudience("https://fess.example.com/mcp/"));
        assertTrue(CanonicalResourceUri.isCompatibleAudience("https://fess.example.com/mcp#fragment"));
    }

    @Test
    public void testCompatibleAudienceRejectsANonMcpPath() {
        // The complement of the test above: what is excluded is an audience ending in something
        // OTHER than the /mcp segment. metadataUrl() would then leave the path untouched and just
        // append the well-known suffix, producing a URL McpMetadataApiManager#matches() never
        // serves (it is an exact match, on the servlet path, against two fixed literals) -- so
        // every challenge would advertise a guaranteed 404.
        assertFalse(CanonicalResourceUri.isCompatibleAudience("https://fess.example.com/api/mcp2"));
        assertFalse(CanonicalResourceUri.isCompatibleAudience("https://fess.example.com"));
        assertFalse(CanonicalResourceUri.isCompatibleAudience("urn:fess-resource"));
    }
}
