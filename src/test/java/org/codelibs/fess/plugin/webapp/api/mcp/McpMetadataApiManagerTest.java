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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.mcp.json.Json;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpMetadataApiManager}: the RFC 9728 protected-resource metadata endpoint.
 */
public class McpMetadataApiManagerTest {

    /** Test double: overrides every {@code ComponentUtil}-touching primitive, not the higher-level methods built on them. */
    static class TestManager extends McpMetadataApiManager {
        final Map<String, String> properties = new HashMap<>();
        Set<String> trustedProxies = Set.of();

        @Override
        protected String getSystemProperty(final String key, final String defaultValue) {
            return properties.getOrDefault(key, defaultValue);
        }

        @Override
        protected Set<String> getTrustedProxies() {
            return trustedProxies;
        }
    }

    private static TestManager oauthManager() {
        final TestManager manager = new TestManager();
        manager.properties.put("mcp.auth.mode", "oauth");
        manager.properties.put("mcp.oauth.issuer", "https://idp.example.com");
        manager.properties.put("mcp.oauth.audience", "https://fess.example.com/mcp");
        return manager;
    }

    // ------------------------------------------------------------------
    // matches(): exact match only, on exactly two paths.
    // ------------------------------------------------------------------

    @Test
    public void testMetadataManagerMatchesExactPathsOnly() {
        final McpMetadataApiManager manager = new McpMetadataApiManager();
        assertTrue(manager.matches(McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource")));
        assertTrue(manager.matches(McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp")));
        assertFalse(manager.matches(McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/other")),
                "registration order is DI-load-order dependent, so this must not shadow anything");
    }

    @Test
    public void testMetadataManagerDoesNotMatchAPrefixOfItsOwnPath() {
        final McpMetadataApiManager manager = new McpMetadataApiManager();
        assertFalse(manager.matches(McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource-typo")));
        assertFalse(manager.matches(McpHttpTestSupport.newRequest("GET", "/.well-known")));
        assertFalse(manager.matches(McpHttpTestSupport.newRequest("GET", "/mcp")));
    }

    // ------------------------------------------------------------------
    // 404: not oauth mode.
    // ------------------------------------------------------------------

    @Test
    public void testMetadataIs404WhenAuthModeIsNotOauth() throws Exception {
        final McpMetadataApiManager manager = new McpMetadataApiManager() {
            @Override
            protected String getAuthMode() {
                return "none";
            }
        };
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        manager.process(request, response, null);
        assertEquals(404, response.getStatus());
    }

    // ------------------------------------------------------------------
    // 404: oauth mode configured but unusable (issuer unset) -- "pin both directions".
    // ------------------------------------------------------------------

    @Test
    public void testMetadataIs404WhenOauthModeHasNoIssuerConfigured() throws Exception {
        // A protected-resource metadata document with an empty authorization_servers array is
        // worse than not serving one at all (RFC 9728 requires it non-empty). This manager reads
        // mcp.oauth.issuer independently of McpApiManager#getAuthenticator, so it must
        // independently refuse to serve a broken document rather than trusting the two config
        // reads can never disagree.
        final TestManager manager = new TestManager();
        manager.properties.put("mcp.auth.mode", "oauth");
        // mcp.oauth.issuer intentionally left unset.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        manager.process(request, response, null);
        assertEquals(404, response.getStatus());
    }

    @Test
    public void testMetadataIs404WhenConfiguredAudienceDoesNotEndInMcp() throws Exception {
        // The bug a reviewer found: an audience whose path is not /mcp would make this
        // document's own "resource" field advertise a resource_metadata URL matches() never
        // serves. Refusing to serve the document at all is safer than serving a self-inconsistent
        // one.
        final TestManager manager = new TestManager();
        manager.properties.put("mcp.auth.mode", "oauth");
        manager.properties.put("mcp.oauth.issuer", "https://idp.example.com");
        manager.properties.put("mcp.oauth.audience", "https://fess.example.com/api/mcp2");
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        manager.process(request, response, null);
        assertEquals(404, response.getStatus());
    }

    // ------------------------------------------------------------------
    // 200: oauth mode usable -- the positive control proving the 404 above is not unconditional.
    // ------------------------------------------------------------------

    @Test
    public void testMetadataIs200WithPrmDocumentWhenOauthModeIsUsable() throws Exception {
        final TestManager manager = oauthManager();
        manager.properties.put("mcp.oauth.required.scopes", "fess:search,fess:admin");
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        assertEquals(200, response.getStatus());
        assertEquals("application/json; charset=UTF-8", response.getContentType());
        final Map<String, Object> body = Json.parseObject(McpHttpTestSupport.bodyOf(response));
        assertEquals("https://fess.example.com/mcp", body.get("resource"));
        assertEquals(List.of("https://idp.example.com"), body.get("authorization_servers"));
        assertEquals(List.of("header"), body.get("bearer_methods_supported"));
        @SuppressWarnings("unchecked")
        final List<String> scopes = (List<String>) body.get("scopes_supported");
        assertTrue(scopes.containsAll(List.of("fess:search", "fess:admin")));
    }

    @Test
    public void testMetadataServesTheSameDocumentAtTheRootWellKnownPath() throws Exception {
        // The bare root path is served for compatibility with clients that probe it before a
        // resource-scoped one; this server has exactly one resource, so the content is identical.
        final TestManager manager = oauthManager();
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        assertEquals(200, response.getStatus());
        final Map<String, Object> body = Json.parseObject(McpHttpTestSupport.bodyOf(response));
        assertEquals("https://fess.example.com/mcp", body.get("resource"));
    }

    @Test
    public void testScopesSupportedNeverContainsOfflineAccessEvenIfMisconfigured() throws Exception {
        final TestManager manager = oauthManager();
        manager.properties.put("mcp.oauth.required.scopes", "fess:search,offline_access");
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        final Map<String, Object> body = Json.parseObject(McpHttpTestSupport.bodyOf(response));
        assertFalse(body.get("scopes_supported").toString().contains("offline_access"));
    }

    // ------------------------------------------------------------------
    // Canonical URI: mcp.oauth.audience is now REQUIRED (C1) -- this manager must refuse to
    // derive the served "resource" field from the request at all, the same way
    // OAuthResourceServerAuthenticator#isUsable() now refuses to select this mode without an
    // explicit audience. Before C1, these two scenarios were pinned as 200-with-a-derived-
    // resource-field; that was itself part of the vulnerability (an unauthenticated caller could
    // make this document reflect an attacker-chosen Host), so the fix changes what these tests
    // assert, not the production code that would keep them passing.
    // ------------------------------------------------------------------

    @Test
    public void testMetadataIs404WhenOauthModeHasNoAudienceConfigured() throws Exception {
        // C1: was testResourceFieldDerivedFromRequestWhenNoAudienceConfigured, which pinned the
        // Host-derived "resource" field as correct behaviour -- exactly the hole C1 closed. This
        // manager reads mcp.oauth.audience independently of OAuthResourceServerAuthenticator, so
        // it must independently refuse here too (see McpMetadataApiManager#process).
        final TestManager manager = new TestManager();
        manager.properties.put("mcp.auth.mode", "oauth");
        manager.properties.put("mcp.oauth.issuer", "https://idp.example.com");
        // mcp.oauth.audience intentionally left unset.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        request.setScheme("https");
        request.setServerName("derived.example.com");
        request.setServerPort(443);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        assertEquals(404, response.getStatus());
    }

    @Test
    public void testMetadataIs404WhenNoAudienceConfiguredEvenWithForwardedHostHeaders() throws Exception {
        // C1: was testUntrustedForwardedHostDoesNotChangeTheServedResourceField, which asserted
        // the served "resource" field fell back to the internal (non-forwarded) host. That is no
        // longer reachable at all: with mcp.oauth.audience unset, this manager now refuses before
        // ever calling resolveCanonicalUri, regardless of what any header (forwarded or not) says.
        final TestManager manager = new TestManager();
        manager.properties.put("mcp.auth.mode", "oauth");
        manager.properties.put("mcp.oauth.issuer", "https://idp.example.com");
        manager.trustedProxies = Set.of("10.0.0.1");
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(80);
        request.setRemoteAddr("203.0.113.9"); // not a trusted proxy
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "attacker.example.com");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        assertEquals(404, response.getStatus());
    }

    @Test
    public void testConfiguredAudienceResourceFieldIgnoresForeignHostHeader() throws Exception {
        // C1 positive control / regression guard for this manager's own call to
        // CanonicalResourceUri.resolve(): once an operator has pinned mcp.oauth.audience, the
        // served "resource" field must be exactly that value, with zero influence from Host --
        // not even a direct (non-forwarded) Host claiming to be a completely different resource.
        final TestManager manager = oauthManager(); // issuer + audience=https://fess.example.com/mcp
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("GET", "/.well-known/oauth-protected-resource/mcp");
        request.setScheme("https");
        request.setServerName("attacker.example.com");
        request.setServerPort(443);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        manager.process(request, response, null);

        assertEquals(200, response.getStatus());
        final Map<String, Object> body = Json.parseObject(McpHttpTestSupport.bodyOf(response));
        assertEquals("https://fess.example.com/mcp", body.get("resource"),
                "the configured audience must win regardless of what Host the caller sends");
    }
}
