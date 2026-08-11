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
 *
 * <p>
 * Most tests here deliberately leave the request's scheme/server name/port at their mock
 * defaults: the allowlist is the entire decision, so nothing the request itself carries may
 * change the outcome. {@link #testOriginMatchingTheHostHeaderIsStillRejected} is the one that
 * pins that directly.
 * </p>
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
    public void testConfiguredOriginIsAllowed() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "https://client.example.com");
        OriginValidator.validate(request, Set.of("https://client.example.com"));
    }

    @Test
    public void testOriginMatchingTheHostHeaderIsStillRejected() {
        // The DNS-rebinding case Origin validation exists to stop, and the reason this class has
        // no self-origin branch. getServerName()/getServerPort() report the Host header, which the
        // caller chooses: an attacker page on http://evil.example:8080 that rebinds evil.example
        // to the Fess host arrives with Host: evil.example:8080 and Origin: http://evil.example:8080.
        // Those two agree by construction, so a self-origin comparison would admit it. Only the
        // configured allowlist -- which does not contain it -- may decide.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("http");
        request.setServerName("evil.example");
        request.setServerPort(8080);
        request.addHeader("Origin", "http://evil.example:8080");

        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));

        assertEquals(403, error.getHttpStatus(), "an Origin must never be trusted because it agrees with the Host header");
    }

    @Test
    public void testServerOwnOriginIsRejectedWhenNotConfigured() {
        // The deliberate behaviour change: a browser client served from the Fess host itself is no
        // longer implicitly allowed, because "the Fess host itself" is only knowable from the same
        // caller-controlled Host header. It must be listed in mcp.allowed.origins like any other.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "https://fess.example.com");

        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of()));

        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testServerOwnOriginIsAllowedWhenConfigured() {
        // ...and listing it is all it takes: the same request the previous test rejects passes
        // once the origin is configured, so the change costs a deployment one config entry rather
        // than the ability to serve a browser client at all.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        request.addHeader("Origin", "https://fess.example.com");

        OriginValidator.validate(request, Set.of("https://fess.example.com"));
    }

    @Test
    public void testDefaultHttpsPortIsEquivalentToNoPort() {
        // Browsers omit the default port when sending Origin, so an allowlist entry written with
        // an explicit ":443" must still match. The Origin header below deliberately omits the port
        // and the allowlist entry deliberately carries it -- if both sides wrote it the same way,
        // a plain string comparison would match even without the "omit the scheme default" rule,
        // and this test would not exercise that rule at all.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "https://client.example.com");
        OriginValidator.validate(request, Set.of("https://client.example.com:443"));
    }

    @Test
    public void testDefaultHttpPortIsEquivalentToNoPort() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "http://client.example.com");
        OriginValidator.validate(request, Set.of("http://client.example.com:80"));
    }

    @Test
    public void testNonDefaultPortMustMatchExactly() {
        // Only the scheme's own default port is dropped; 8443 is significant, so an Origin with no
        // port is a different origin from an allowlist entry naming 8443.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "https://client.example.com");
        final McpError error =
                assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of("https://client.example.com:8443")));
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
        request.addHeader("Origin", "https://client.example.com/some/path");
        OriginValidator.validate(request, Set.of("https://client.example.com"));
    }

    @Test
    public void testSuffixSpoofedOriginIsRejectedWith403() {
        // "client.example.com.evil.com" ends with the trusted host as a substring; exact equality
        // must reject it rather than an endsWith-style check that would accept it.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "https://client.example.com.evil.com");
        final McpError error = assertThrows(McpError.class, () -> OriginValidator.validate(request, Set.of("https://client.example.com")));
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    public void testUppercaseSchemeAndHostMatchLowercaseAllowlistEntry() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "HTTPS://CLIENT.EXAMPLE.COM");
        OriginValidator.validate(request, Set.of("https://client.example.com"));
    }

    @Test
    public void testUppercaseAllowlistEntryMatchesLowercaseOrigin() {
        // The allowlist comes from hand-written configuration, so it is at least as likely to
        // carry stray case as the header is; normalisation is applied to both sides.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Origin", "https://client.example.com");
        OriginValidator.validate(request, Set.of("HTTPS://CLIENT.EXAMPLE.COM"));
    }
}
