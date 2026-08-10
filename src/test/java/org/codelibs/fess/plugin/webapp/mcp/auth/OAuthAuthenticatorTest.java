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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.plugin.webapp.api.mcp.McpHttpTestSupport;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Tests for {@link OAuthResourceServerAuthenticator} and {@link ProtectedResourceMetadata}.
 * <p>
 * JWTs are generated and signed in-process with a real RSA key pair (nimbus's
 * {@code RSAKeyGenerator}/{@code RSASSASigner}/{@code SignedJWT}) and verified through a real
 * {@code DefaultJWTProcessor} backed by an in-memory {@code ImmutableJWKSet} -- no network call,
 * and the signature-verification code path is genuinely exercised rather than mocked away. Only
 * {@link OAuthResourceServerAuthenticator#getProcessor()} (the seam that would otherwise reach a
 * real JWKS URL over HTTP) is overridden by {@link TestAuthenticator}.
 * </p>
 */
public class OAuthAuthenticatorTest {

    private static final String ISSUER = "https://idp.example.com";

    private static final String AUDIENCE = "https://fess.example.com/mcp";

    private static RSAKey signingKey;

    private static RSAKey otherKey;

    @BeforeAll
    public static void generateKeys() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
    }

    /**
     * Test double: overrides every {@code ComponentUtil}-touching primitive
     * ({@code getSystemProperty}/{@code getSystemPropertyAsInt}/{@code getTrustedProxies}), not
     * the higher-level parsing methods built on them, so the real production parsing logic
     * (comma-split required scopes, the scope-permission-map grammar, ...) is actually exercised
     * -- the same lesson this plugin's {@code McpApiManager} test suite already applies to
     * {@code getAuthMode()}. {@link #getProcessor()} is the one deliberate exception: it is the
     * seam that would otherwise reach a real JWKS URL over HTTP, so it is replaced with an
     * in-memory key source instead.
     */
    static class TestAuthenticator extends OAuthResourceServerAuthenticator {
        final Map<String, String> properties = new HashMap<>();
        Set<String> trustedProxies = Set.of();
        RSAKey verificationKey = signingKey;

        @Override
        protected String getSystemProperty(final String key, final String defaultValue) {
            return properties.getOrDefault(key, defaultValue);
        }

        @Override
        protected int getSystemPropertyAsInt(final String key, final int defaultValue) {
            final String value = properties.get(key);
            return value == null ? defaultValue : Integer.parseInt(value);
        }

        @Override
        protected Set<String> getTrustedProxies() {
            return trustedProxies;
        }

        @Override
        protected ConfigurableJWTProcessor<SecurityContext> getProcessor() {
            final ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                    new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(verificationKey.toPublicJWK()))));
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, Set.of(JWTClaimNames.EXPIRATION_TIME)));
            return processor;
        }
    }

    private static TestAuthenticator newAuthenticator(final String requiredScopes) {
        final TestAuthenticator auth = new TestAuthenticator();
        auth.properties.put("mcp.oauth.issuer", ISSUER);
        auth.properties.put("mcp.oauth.audience", AUDIENCE);
        auth.properties.put("mcp.oauth.required.scopes", requiredScopes);
        return auth;
    }

    private static TestAuthenticator newAuthenticatorWithIssuer(final String issuer) {
        final TestAuthenticator auth = new TestAuthenticator();
        auth.properties.put("mcp.oauth.issuer", issuer);
        return auth;
    }

    private static JWTClaimsSet.Builder validClaims() {
        final Date now = new Date();
        return new JWTClaimsSet.Builder().issuer(ISSUER)
                .subject("user-123")
                .audience(AUDIENCE)
                .expirationTime(new Date(now.getTime() + 3_600_000L))
                .issueTime(now);
    }

    private static String sign(final JWTClaimsSet.Builder claims, final RSAKey key) throws JOSEException {
        final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static MockletHttpServletRequestImpl request() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setScheme("https");
        request.setServerName("fess.example.com");
        request.setServerPort(443);
        return request;
    }

    private static MockletHttpServletRequestImpl bearerRequest(final String token) {
        final MockletHttpServletRequestImpl request = request();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    // ------------------------------------------------------------------
    // Canonical URI: independent of the request path.
    // ------------------------------------------------------------------

    @Test
    public void testAudienceIsIndependentOfTheRequestPath() {
        final MockletHttpServletRequestImpl root = McpHttpTestSupport.newRequest("POST", "/mcp");
        root.setScheme("https");
        root.setServerName("fess.example.com");
        root.setServerPort(443);
        final MockletHttpServletRequestImpl sub = McpHttpTestSupport.newRequest("POST", "/mcp/x");
        sub.setScheme("https");
        sub.setServerName("fess.example.com");
        sub.setServerPort(443);

        // No mcp.oauth.audience configured: this must exercise the request-derived branch, not
        // just prove a configured override wins regardless of path (which testConfiguredAudience-
        // TakesPriorityOverRequest in CanonicalResourceUriTest already covers). Without this, a
        // mutant that derives the audience from request.getServletPath() instead of the literal
        // /mcp suffix would pass this test vacuously, since both assertions would short-circuit
        // on the configured-audience branch before ever reaching the derivation this test names.
        final OAuthResourceServerAuthenticator auth = newAuthenticatorWithIssuer(ISSUER);
        assertEquals("https://fess.example.com/mcp", auth.resolveCanonicalUri(root));
        assertEquals("https://fess.example.com/mcp", auth.resolveCanonicalUri(sub), "a token bound to /mcp must not fail at /mcp/x");
    }

    // ------------------------------------------------------------------
    // Missing credential: RFC 6750 §3 bare challenge, but still carries scope + resource_metadata.
    // ------------------------------------------------------------------

    @Test
    public void testMissingTokenGetsBareChallengeWithNoErrorCode() {
        final MockletHttpServletRequestImpl request = request();
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        final McpError error = assertThrows(McpError.class, () -> newAuthenticator("fess:search").authenticate(request, response));

        assertEquals(401, error.getHttpStatus());
        final String challenge = response.getHeader("WWW-Authenticate");
        assertFalse(challenge.contains("error="),
                "RFC 6750 §3: no credential was supplied at all, so no error code may be present -- invalid_token would "
                        + "claim a bad credential WAS supplied: " + challenge);
    }

    @Test
    public void testMissingTokenChallengeStillCarriesScopeAndResourceMetadataForDiscovery() {
        final MockletHttpServletRequestImpl request = request();
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        assertThrows(McpError.class, () -> newAuthenticator("fess:search").authenticate(request, response));

        final String challenge = response.getHeader("WWW-Authenticate");
        assertTrue(challenge.contains("scope=\"fess:search\""),
                "a client with no token yet still needs to know what to request: " + challenge);
        assertTrue(challenge.contains("resource_metadata=\"https://fess.example.com/.well-known/oauth-protected-resource/mcp\""),
                challenge);
    }

    // ------------------------------------------------------------------
    // Unusable configuration: an unset issuer falls back to none (checked by McpApiManager).
    // ------------------------------------------------------------------

    @Test
    public void testOauthModeWithoutAnIssuerFallsBackToNone() {
        // The PRM document MUST list at least one authorization server. Serving an empty
        // one is worse than not enabling authorization at all.
        assertFalse(newAuthenticatorWithIssuer("").isUsable());
    }

    @Test
    public void testOauthModeWithAnIssuerIsUsable() {
        // Non-tautological companion: isUsable() is not simply always false.
        assertTrue(newAuthenticatorWithIssuer(ISSUER).isUsable());
    }

    // ------------------------------------------------------------------
    // PRM document shape.
    // ------------------------------------------------------------------

    @Test
    public void testPrmDocumentShape() {
        final Map<String, Object> prm =
                new ProtectedResourceMetadata("https://fess.example.com/mcp", "https://idp.example.com", Set.of("fess:search")).toMap();
        assertEquals("https://fess.example.com/mcp", prm.get("resource"));
        assertEquals(List.of("https://idp.example.com"), prm.get("authorization_servers"));
        assertEquals(List.of("header"), prm.get("bearer_methods_supported"));
        assertFalse(prm.get("scopes_supported").toString().contains("offline_access"), "the spec says SHOULD NOT");
    }

    @Test
    public void testPrmDocumentFiltersOfflineAccessScopeEvenIfConfigured() {
        // Stronger than testPrmDocumentShape: proves ProtectedResourceMetadata itself strips
        // offline_access, not merely that nobody happened to pass it in.
        final Map<String, Object> prm = new ProtectedResourceMetadata("https://fess.example.com/mcp", "https://idp.example.com",
                Set.of("fess:search", "offline_access")).toMap();
        @SuppressWarnings("unchecked")
        final List<String> scopes = (List<String>) prm.get("scopes_supported");
        assertEquals(List.of("fess:search"), scopes);
    }

    // ------------------------------------------------------------------
    // Valid token: the positive control.
    // ------------------------------------------------------------------

    @Test
    public void testValidTokenAuthenticatesAndResolvesSubjectAndScopes() throws Exception {
        final String token = sign(validClaims().claim("scope", "fess:search fess:admin"), signingKey);
        final TestAuthenticator auth = newAuthenticator("fess:search");
        final MockletHttpServletRequestImpl request = bearerRequest(token);

        final McpPrincipal principal = auth.authenticate(request, McpHttpTestSupport.newResponse(request));

        assertEquals("user-123", principal.getSubject());
        assertEquals(Set.of("fess:search", "fess:admin"), principal.getScopes());
        assertTrue(auth.ownsRoleResolution());
    }

    // ------------------------------------------------------------------
    // Audience (RFC 8707): a token minted for a different resource must be rejected.
    // ------------------------------------------------------------------

    @Test
    public void testTokenMintedForADifferentResourceIsRejected() throws Exception {
        final String token = sign(validClaims().audience("https://other-resource.example.com/mcp"), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
        assertTrue(response.getHeader("WWW-Authenticate").contains("error=\"invalid_token\""));
    }

    @Test
    public void testUntrustedForwardedHostCannotWidenTheAudienceAccepted() throws Exception {
        // The attack this guards against: a token legitimately minted (by the SAME issuer) for a
        // different resource must not become acceptable here just because an untrusted client
        // claims (via X-Forwarded-Host) to be talking to that other resource's hostname.
        final String token = sign(validClaims().audience("https://attacker.example.com/mcp"), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        auth.trustedProxies = Set.of("10.0.0.1"); // configured, but this caller is not it
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "attacker.example.com");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus(),
                "an untrusted X-Forwarded-Host must not be able to rewrite the audience this server checks against");
    }

    // ------------------------------------------------------------------
    // Signature.
    // ------------------------------------------------------------------

    @Test
    public void testWrongSignatureIsRejected() throws Exception {
        // Signed with a key that is NOT in the verifier's JWKSet.
        final String token = sign(validClaims(), otherKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
    }

    // ------------------------------------------------------------------
    // Issuer.
    // ------------------------------------------------------------------

    @Test
    public void testWrongIssuerIsRejected() throws Exception {
        final String token = sign(validClaims().issuer("https://not-the-configured-issuer.example.com"), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
    }

    // ------------------------------------------------------------------
    // exp / nbf.
    // ------------------------------------------------------------------

    @Test
    public void testExpiredTokenIsRejected() throws Exception {
        final Date past = new Date(System.currentTimeMillis() - 3_600_000L);
        final String token =
                sign(new JWTClaimsSet.Builder().issuer(ISSUER).subject("user-123").audience(AUDIENCE).expirationTime(past), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
    }

    @Test
    public void testTokenWithoutAnExpirationClaimIsRejected() throws Exception {
        // No exp claim at all: DefaultJWTClaimsVerifier is configured with exp as a required
        // claim, so this must fail even though there is no expiration time to compare against.
        final String token = sign(new JWTClaimsSet.Builder().issuer(ISSUER).subject("user-123").audience(AUDIENCE), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
    }

    @Test
    public void testNotYetValidTokenIsRejected() throws Exception {
        final Date future = new Date(System.currentTimeMillis() + 3_600_000L);
        final String token = sign(validClaims().notBeforeTime(future), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
    }

    // ------------------------------------------------------------------
    // Required scopes.
    // ------------------------------------------------------------------

    @Test
    public void testInsufficientScopeIsRejectedWith403AndAllRequiredScopes() throws Exception {
        final String token = sign(validClaims().claim("scope", "fess:search"), signingKey);
        final TestAuthenticator auth = newAuthenticator("fess:search,fess:admin");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(403, error.getHttpStatus());
        final String challenge = response.getHeader("WWW-Authenticate");
        assertTrue(challenge.contains("error=\"insufficient_scope\""), challenge);
        assertTrue(challenge.contains("scope=\"fess:search fess:admin\""), "must emit ALL needed scopes in one challenge: " + challenge);
        assertTrue(challenge.contains("resource_metadata="), challenge);
    }

    @Test
    public void testTokenWithExtraScopesIsAccepted() throws Exception {
        final String token = sign(validClaims().claim("scope", "fess:search fess:admin fess:extra"), signingKey);
        final TestAuthenticator auth = newAuthenticator("fess:search,fess:admin");
        final MockletHttpServletRequestImpl request = bearerRequest(token);

        final McpPrincipal principal = auth.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertEquals(Set.of("fess:search", "fess:admin", "fess:extra"), principal.getScopes());
    }

    @Test
    public void testNoScopeClaimWithNoRequiredScopesIsAccepted() throws Exception {
        final String token = sign(validClaims(), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);

        final McpPrincipal principal = auth.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertTrue(principal.getScopes().isEmpty());
    }

    // ------------------------------------------------------------------
    // Permission mapping: union of the claim and the scope map; empty is not compensated here.
    // ------------------------------------------------------------------

    @Test
    public void testPermissionClaimListContributesPermissions() throws Exception {
        final String token = sign(validClaims().claim("fess_permissions", List.of("Rfoo", "Rbar")), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        auth.properties.put("mcp.oauth.permission.claim", "fess_permissions");
        final MockletHttpServletRequestImpl request = bearerRequest(token);

        final McpPrincipal principal = auth.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertEquals(Set.of("Rfoo", "Rbar"), principal.getPermissions());
    }

    @Test
    public void testScopePermissionMapContributesPermissionsForTokenScopes() throws Exception {
        final String token = sign(validClaims().claim("scope", "fess:search"), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        auth.properties.put("mcp.oauth.scope.permission.map", "fess:search=Rguest,fess:search=1guest,fess:admin=Radmin-api");
        final MockletHttpServletRequestImpl request = bearerRequest(token);

        final McpPrincipal principal = auth.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertEquals(Set.of("Rguest", "1guest"), principal.getPermissions(),
                "only the token's own scopes (fess:search) contribute -- fess:admin's mapping must not leak in");
    }

    @Test
    public void testPermissionsAreTheUnionOfClaimAndScopeMap() throws Exception {
        final String token = sign(validClaims().claim("scope", "fess:search").claim("fess_permissions", List.of("Rextra")), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        auth.properties.put("mcp.oauth.permission.claim", "fess_permissions");
        auth.properties.put("mcp.oauth.scope.permission.map", "fess:search=Rguest");
        final MockletHttpServletRequestImpl request = bearerRequest(token);

        final McpPrincipal principal = auth.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertEquals(Set.of("Rextra", "Rguest"), principal.getPermissions());
    }

    @Test
    public void testNoPermissionSourcesConfiguredResolvesToEmptyPermissions() throws Exception {
        // The empty-permission-set compensation (guest role fallback, default permissions) is
        // McpApiManager#resolveRoles's job, reached because ownsRoleResolution() is true -- this
        // class itself must not duplicate that logic, only report what it actually found.
        final String token = sign(validClaims().claim("scope", "fess:search"), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);

        final McpPrincipal principal = auth.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertTrue(principal.getPermissions().isEmpty());
    }

    // ------------------------------------------------------------------
    // No network: getJwksUri()/newProcessor() are never reached from these tests.
    // ------------------------------------------------------------------

    @Test
    public void testRealClaimsVerifierRequiresExpirationClaim() throws Exception {
        // newProcessor() itself is never reached from this suite (it would need a real JWKS
        // URL), so without exercising newClaimsVerifier() directly, nothing here would prove
        // the real (non-test-double) production wiring actually requires exp -- only that this
        // file's own copy of the same configuration, in TestAuthenticator#getProcessor(), does.
        final OAuthResourceServerAuthenticator auth = newAuthenticatorWithIssuer(ISSUER);
        final JWTClaimsSet.Builder withoutExp = new JWTClaimsSet.Builder().issuer(ISSUER).subject("user-123").audience(AUDIENCE);
        assertThrows(Exception.class, () -> auth.newClaimsVerifier().verify(withoutExp.build(), null));

        final JWTClaimsSet.Builder withExp = validClaims();
        // Must not throw.
        auth.newClaimsVerifier().verify(withExp.build(), null);
    }

    @Test
    public void testDefaultJwksCacheSecondsLiteral() {
        // Real body of getJwksCacheSeconds(), exercised container-free the same way
        // McpApiManager's own getAuthMode() default is: via the getSystemProperty seam, not by
        // overriding getJwksCacheSeconds() itself.
        final TestAuthenticator auth = new TestAuthenticator();
        assertEquals(300, auth.getJwksCacheSeconds());
    }

    @Test
    public void testBlankIssuerAndJwksUriAreTheRealDefaults() {
        final TestAuthenticator auth = new TestAuthenticator();
        assertEquals("", auth.getIssuer());
        assertEquals("", auth.getJwksUri());
        assertFalse(auth.isUsable());
    }
}
