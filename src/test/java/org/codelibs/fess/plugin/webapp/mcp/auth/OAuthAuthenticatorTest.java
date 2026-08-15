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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
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
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.CachingJWKSetSource;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSetBasedJWKSource;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSetSourceWrapper;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RetryingJWKSetSource;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;

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
        // Deliberately the SAME kid as signingKey: testWrongSignatureIsRejected must fail on
        // signature verification itself, not on key SELECTION never finding a matching kid.
        otherKey = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
    }

    /**
     * Test double: overrides every {@code ComponentUtil}-touching primitive
     * ({@code getSystemProperty}/{@code getSystemPropertyAsInt}/{@code getTrustedProxies}), not
     * the higher-level parsing methods built on them, so the real production parsing logic
     * (comma-split required scopes, the scope-permission-map grammar, ...) is actually exercised
     * -- the same lesson this plugin's {@code McpApiManager} test suite already applies to
     * {@code getAuthMode()}.
     * <p>
     * This class deliberately overrides <em>nothing else</em>, so
     * {@link OAuthResourceServerAuthenticator#getProcessor()} -- including its config-keyed
     * caching -- and {@link OAuthResourceServerAuthenticator#newProcessor(String)} both run
     * exactly as they ship. That is what the JWKS-source tests at the bottom of this file need,
     * and it is why the in-memory key source lives in {@link TestAuthenticator} below rather
     * than here: a double that replaces {@code getProcessor()} can never observe what
     * {@code getProcessor()} actually does. No network happens from here either --
     * {@link JWKSourceBuilder} resolves the URL lazily, on the first key lookup, which none of
     * those tests performs.
     * </p>
     */
    static class ConfigOnlyAuthenticator extends OAuthResourceServerAuthenticator {
        final Map<String, String> properties = new HashMap<>();
        Set<String> trustedProxies = Set.of();

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
    }

    /**
     * {@link ConfigOnlyAuthenticator} plus the one deliberate extra seam every token-verifying
     * test needs: {@link #getProcessor()}, which would otherwise reach a real JWKS URL over HTTP.
     * Even there, it delegates to the real
     * {@link OAuthResourceServerAuthenticator#newProcessor(JWKSource)} with an in-memory key
     * source, rather than re-implementing that assembly here -- a hand-maintained duplicate would
     * verify only itself, never the production wiring (key selector, RS256 restriction, claims
     * verifier) that actually ships.
     */
    static class TestAuthenticator extends ConfigOnlyAuthenticator {
        RSAKey verificationKey = signingKey;

        /**
         * An additional, non-RSA key to publish in the in-memory JWKS. Only
         * {@link OAuthAuthenticatorTest#testHmacSignedTokenIsRejectedEvenWhenTheJwksPublishesASymmetricKey}
         * sets it; it exists because an RSA-only key set already rejects a MAC-signed token for
         * an unrelated reason, which would make that test pin nothing.
         */
        JWK extraVerificationKey;

        @Override
        protected ConfigurableJWTProcessor<SecurityContext> getProcessor() {
            final List<JWK> keys = extraVerificationKey == null ? List.of(verificationKey.toPublicJWK())
                    : List.of(verificationKey.toPublicJWK(), extraVerificationKey);
            final JWKSource<SecurityContext> inMemorySource = new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(keys));
            return newProcessor(inMemorySource);
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
        return sign(claims, key, JWSAlgorithm.RS256);
    }

    private static String sign(final JWTClaimsSet.Builder claims, final RSAKey key, final JWSAlgorithm algorithm) throws JOSEException {
        return sign(claims, key, algorithm, null);
    }

    /**
     * Signs with RS256 and an explicit JOSE {@code typ} header. The {@code typ}-less overloads
     * above deliberately emit no {@code typ} at all, which is why every pre-existing test in this
     * file exercised only nimbus's "absent {@code typ}" path and none of them could have noticed
     * that an RFC 9068 {@code at+jwt} access token was being rejected.
     *
     * @param claims the claims to sign
     * @param key the RSA key to sign with
     * @param type the {@code typ} header value; {@code null} omits the header entirely
     * @return the serialised JWT
     * @throws JOSEException if signing fails
     */
    private static String sign(final JWTClaimsSet.Builder claims, final RSAKey key, final JOSEObjectType type) throws JOSEException {
        return sign(claims, key, JWSAlgorithm.RS256, type);
    }

    private static String sign(final JWTClaimsSet.Builder claims, final RSAKey key, final JWSAlgorithm algorithm, final JOSEObjectType type)
            throws JOSEException {
        final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(type).build(), claims.build());
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

    /**
     * Authenticates {@code token} against a default (no required scopes) oauth configuration.
     *
     * @param token the serialised JWT to present as a bearer credential
     * @return the resolved principal
     */
    private static McpPrincipal authenticate(final String token) {
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        return auth.authenticate(request, McpHttpTestSupport.newResponse(request));
    }

    /**
     * Asserts that {@code token} is refused with a 401, against the same default configuration
     * {@link #authenticate} accepts a good token under -- so the only thing that can differ
     * between the two is the token itself.
     *
     * @param token the serialised JWT to present as a bearer credential
     * @param message the assertion message explaining why this token must be refused
     */
    private static void assertRejectedWith401(final String token, final String message) {
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response), message);
        assertEquals(401, error.getHttpStatus(), message);
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
    // Unusable configuration: isUsable() requires issuer, audience, and jwks.uri, each checked
    // in isolation (every other requirement is satisfied by usableConfig() so each negative test
    // below fails for exactly one reason, not possibly two at once).
    // ------------------------------------------------------------------

    /** A fully usable oauth configuration: issuer, a /mcp-compatible audience, and a jwks.uri all set. */
    private static TestAuthenticator usableConfig() {
        final TestAuthenticator auth = new TestAuthenticator();
        auth.properties.put("mcp.oauth.issuer", ISSUER);
        auth.properties.put("mcp.oauth.audience", AUDIENCE);
        auth.properties.put("mcp.oauth.jwks.uri", "https://idp.example.com/jwks");
        return auth;
    }

    @Test
    public void testOauthModeWithoutAnIssuerFallsBackToNone() {
        // The PRM document MUST list at least one authorization server. Serving an empty
        // one is worse than not enabling authorization at all.
        final TestAuthenticator auth = usableConfig();
        auth.properties.put("mcp.oauth.issuer", "");
        assertFalse(auth.isUsable());
    }

    @Test
    public void testOauthModeWithoutAnAudienceFallsBackToNone() {
        // C1: mcp.oauth.audience is now REQUIRED, not merely validated-if-present. Without it,
        // OAuthResourceServerAuthenticator#resolveCanonicalUri would derive the RFC 8707 audience
        // from the request's caller-controlled Host header instead -- exactly the confused-deputy
        // hole audience binding exists to close. This is the direct regression guard for isUsable()
        // itself; testForeignHostHeaderCannotMintATrustedAudienceWhenAudienceIsNotConfigured below
        // is the end-to-end guard for the same requirement.
        final TestAuthenticator auth = usableConfig();
        auth.properties.remove("mcp.oauth.audience");
        assertFalse(auth.isUsable());
    }

    @Test
    public void testOauthModeWithAnAudienceNotEndingInMcpFallsBackToNone() {
        // The other half of the audience check: a configured mcp.oauth.audience that does not END
        // IN /mcp would make every challenge advertise a resource_metadata URL nothing serves.
        // Note the value below is /api/mcp2, not /api/mcp -- a leading path segment is perfectly
        // valid (it is what a context-path deployment uses); only the final segment matters. See
        // CanonicalResourceUriTest#testCompatibleAudienceAcceptsAnMcpSuffixedValue.
        final TestAuthenticator auth = usableConfig();
        auth.properties.put("mcp.oauth.audience", "https://fess.example.com/api/mcp2");
        assertFalse(auth.isUsable());
    }

    @Test
    public void testOauthModeWithoutAJwksUriFallsBackToNone() {
        // I1: without this, this authenticator would still be selected, and the first
        // token-bearing request would fail inside getProcessor() -> new URL("") with a generic,
        // misleading 401 -- and, unlike the two checks above, previously fired NO startup
        // diagnostic at all (isUsable() ignored jwks.uri entirely, so the ERROR that fires only
        // when isUsable() is false never ran).
        final TestAuthenticator auth = usableConfig();
        auth.properties.remove("mcp.oauth.jwks.uri");
        assertFalse(auth.isUsable());
    }

    @Test
    public void testOauthModeWithIssuerAudienceAndJwksUriIsUsable() {
        // Positive control for all three negative tests above: isUsable() is not simply always
        // false, only false when one of its three requirements is actually missing.
        assertTrue(usableConfig().isUsable());
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
    public void testForeignHostHeaderCannotMintATrustedAudienceWhenAudienceIsNotConfigured() throws Exception {
        // C1's regression guard. The attack: with mcp.oauth.audience unset (its documented
        // default), the raw, directly-observed Host header IS request.getServerName() -- a caller
        // sending a direct request fully controls it. An attacker holding a token legitimately
        // minted (by the SAME issuer) for a DIFFERENT resource simply sends that resource's own
        // hostname as Host: the pre-C1 resolveCanonicalUri would derive exactly that hostname as
        // the canonical audience, which then trivially equals the token's real aud, and the
        // attacker is admitted.
        //
        // Also covers the X-Forwarded-Host variant of the same attack in one test (folded in from
        // a formerly separate testUntrustedForwardedHostCannotWidenTheAudienceAccepted): that
        // test used newAuthenticatorWithIssuer too, so once C1's fix made an unconfigured
        // audience refuse via audienceNotConfigured() before any header at all is read, both
        // scenarios started failing for the exact same reason and could no longer be
        // distinguished from each other by any mutant -- keeping them as two separately-named
        // tests was itself becoming the same "test cannot fail for the reason its name states"
        // defect I2 was originally raised for. The forwarded-header channel's own trust boundary
        // (an untrusted proxy's X-Forwarded-Host must not be honoured) remains fully covered by
        // CanonicalResourceUriTest#testUntrustedRemoteAddrIgnoresForwardedHeaders and its
        // trusted-proxy siblings, which call resolve() directly and are unaffected by this guard.
        final MockletHttpServletRequestImpl request = request();
        request.setServerName("other-mcp.example.com");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "attacker.example.com");
        final String token = sign(validClaims().audience("https://other-mcp.example.com/mcp"), signingKey);
        request.addHeader("Authorization", "Bearer " + token);
        final TestAuthenticator auth = newAuthenticatorWithIssuer(ISSUER); // mcp.oauth.audience deliberately unset
        auth.trustedProxies = Set.of("10.0.0.1"); // configured, but this caller is not it either
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response),
                "a Host-header-spoofed audience must never be accepted, even though the token's iss/sig/exp are all "
                        + "genuinely valid for that spoofed resource");
        assertEquals(401, error.getHttpStatus());
    }

    // ------------------------------------------------------------------
    // RFC 9068 §4 "typ": at+jwt / application/at+jwt must be accepted, and the two values that
    // were already accepted before the typ verifier was installed must stay accepted.
    // ------------------------------------------------------------------

    @Test
    public void testRfc9068AccessTokenTypIsAccepted() throws Exception {
        // RFC 9068 §4: "The resource server MUST verify that the "typ" header value is "at+jwt"
        // or "application/at+jwt" and reject tokens carrying any other value." Nimbus's default
        // when no JWS type verifier is set is DefaultJOSEObjectTypeVerifier.JWT, which allows
        // ONLY "JWT" or an absent typ -- i.e. it rejects precisely the value the RFC mandates for
        // a JWT access token. Every other token test in this file signs without a typ header, so
        // nothing here covered this until now.
        final String token = sign(validClaims(), signingKey, new JOSEObjectType("at+jwt"));
        assertEquals("user-123", authenticate(token).getSubject(), "RFC 9068 §4 mandates at+jwt on a JWT access token");
    }

    @Test
    public void testRfc9068LongFormAccessTokenTypIsAccepted() throws Exception {
        // The other spelling RFC 9068 §4 mandates, in its media-type long form.
        final String token = sign(validClaims(), signingKey, new JOSEObjectType("application/at+jwt"));
        assertEquals("user-123", authenticate(token).getSubject(), "RFC 9068 §4 mandates application/at+jwt equally");
    }

    @Test
    public void testTypIsMatchedCaseInsensitively() throws Exception {
        // JOSEObjectType#equals compares ignoring case, so an authorization server that emits
        // "AT+JWT" is accepted too. Pinned because the allowed set is written in lower case and a
        // future switch to a case-SENSITIVE membership test would silently start 401ing such an AS.
        final String token = sign(validClaims(), signingKey, new JOSEObjectType("AT+JWT"));
        assertEquals("user-123", authenticate(token).getSubject());
    }

    @Test
    public void testAbsentTypIsStillAccepted() throws Exception {
        // The compatibility half of the fix, and the reason going fully strict (at+jwt only) is
        // not viable: this is what the pre-fix default already accepted, and what a large share of
        // real access tokens carry.
        final String token = sign(validClaims(), signingKey, (JOSEObjectType) null);
        assertEquals("user-123", authenticate(token).getSubject(), "a token with no typ header at all must stay acceptable");
    }

    @Test
    public void testPlainJwtTypIsStillAccepted() throws Exception {
        // Entra ID, Auth0, Okta and a default-configured Keycloak all mint ACCESS tokens with
        // typ: JWT, so narrowing the allowed set to at+jwt alone would 401 most real deployments.
        final String token = sign(validClaims(), signingKey, JOSEObjectType.JWT);
        assertEquals("user-123", authenticate(token).getSubject(), "typ: JWT is what most real authorization servers emit");
    }

    @Test
    public void testTypOutsideTheAllowedSetIsStillRejected() throws Exception {
        // The allowed set is widened, not opened: a JWT whose typ declares it to be something
        // other than an access token must not be usable as one. All three below are signed by the
        // right key with a valid iss/aud/exp, so typ is the only thing that can reject them.
        assertRejectedWith401(sign(validClaims(), signingKey, new JOSEObjectType("secevent+jwt")),
                "an RFC 8417 security event token is not an access token");
        assertRejectedWith401(sign(validClaims(), signingKey, new JOSEObjectType("dpop+jwt")),
                "an RFC 9449 DPoP proof is not an access token");
        assertRejectedWith401(sign(validClaims(), signingKey, new JOSEObjectType("ID_TOKEN")), "an arbitrary typ is not an access token");
    }

    // ------------------------------------------------------------------
    // Signature.
    // ------------------------------------------------------------------

    @Test
    public void testWrongSignatureIsRejected() throws Exception {
        // Signed with a DIFFERENT private key that happens to share the same kid as the key in
        // the verifier's JWKSet: the key selector will pick the right JWKSet entry by kid, so
        // this genuinely exercises RSA signature verification math, not just key lookup -- a
        // mismatched-kid token would fail at selection and never reach the crypto check at all.
        final String token = sign(validClaims(), otherKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
    }

    @Test
    public void testTokenSignedWithADifferentRsaAlgorithmIsRejected() throws Exception {
        // Signed with the CORRECT key but RS384, not RS256. The test JWK carries no "alg"
        // constraint of its own, so nimbus's key selector would happily match this key for any
        // RSA-family algorithm unless newProcessor's single-algorithm JWSVerificationKeySelector
        // constructor genuinely restricts acceptance to RS256. This is a stronger check than
        // testWrongSignatureIsRejected: signing with a different key (same kid) fails on
        // signature verification math; this one uses the SAME key but a different algorithm, so
        // it fails only if the algorithm restriction itself is enforced.
        final String token = sign(validClaims(), signingKey, JWSAlgorithm.RS384);
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
        // Non-empty required scopes (unlike every other bad-token test in this file): proves the
        // invalid_token challenge itself still carries scope and resource_metadata, not just the
        // error code. This is the challenge a live client hits most often (e.g. an expired
        // token), and resource_metadata is exactly what it needs to re-discover the AS.
        final TestAuthenticator auth = newAuthenticator("fess:search");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
        final String challenge = response.getHeader("WWW-Authenticate");
        assertTrue(challenge.contains("error=\"invalid_token\""), challenge);
        assertTrue(challenge.contains("scope=\"fess:search\""), "the invalid_token challenge must still carry scope: " + challenge);
        assertTrue(challenge.contains("resource_metadata=\"https://fess.example.com/.well-known/oauth-protected-resource/mcp\""),
                challenge);
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
        // overriding getJwksCacheSeconds() itself. 300 is comfortably above the 60s floor below,
        // so the shipped default is never clamped.
        final TestAuthenticator auth = new TestAuthenticator();
        assertEquals(300, auth.getJwksCacheSeconds());
    }

    // ------------------------------------------------------------------
    // mcp.oauth.jwks.cache.seconds: clamped to nimbus's own lower bound.
    // ------------------------------------------------------------------

    @Test
    public void testSubFloorJwksCacheSecondsIsClampedToSixty() {
        // 60 is not a taste judgement, it is the smallest value JWKSourceBuilder can actually
        // build the source newProcessor(String) asks for: RefreshAheadCachingJWKSetSource rejects
        // a TTL below refreshAheadTime (30s) + cacheRefreshTimeout (30s), and JWKSourceBuilder
        // #build() separately rejects a TTL <= the rate limiter's 30s minimum interval. The three
        // values below straddle both of those bounds (<=30, 31..59, and just under 60).
        final TestAuthenticator auth = new TestAuthenticator();
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "5");
        assertEquals(60, auth.getJwksCacheSeconds(), "a TTL under the rate limiter's 30s interval must be clamped, not passed through");
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "45");
        assertEquals(60, auth.getJwksCacheSeconds(), "a TTL that clears the rate limiter but not refresh-ahead + timeout must clamp too");
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "59");
        assertEquals(60, auth.getJwksCacheSeconds(), "59 is one second short of buildable");
    }

    @Test
    public void testJwksCacheSecondsAtOrAboveTheFloorIsUsedAsConfigured() {
        // Positive control: the clamp is a floor, not a fixed value -- an operator who asks for a
        // longer cache lifetime still gets exactly what they asked for.
        final TestAuthenticator auth = new TestAuthenticator();
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "60");
        assertEquals(60, auth.getJwksCacheSeconds(), "exactly the floor is buildable and must pass through unchanged");
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "3600");
        assertEquals(3600, auth.getJwksCacheSeconds());
    }

    @Test
    public void testSubFloorJwksCacheSecondsStillProducesAWorkingProcessor() throws Exception {
        // The reason the clamp exists, exercised through the REAL newProcessor(String) -- the one
        // production path that consumes getJwksCacheSeconds(). Without the clamp this call throws
        // an unchecked IllegalStateException ("rate limiting min time interval must be less than
        // the cache time-to-live") out of JWKSourceBuilder#build(); getProcessor() catches only
        // MalformedURLException, so it would escape into authenticate()'s broad RuntimeException
        // clause and be laundered into a 401 invalid_token on EVERY request, permanently --
        // cachedProcessor is assigned only after a successful build, so each request retries and
        // fails identically. No network happens here: JWKSourceBuilder resolves the URL lazily,
        // on the first key lookup, which this test never performs.
        final TestAuthenticator auth = new TestAuthenticator();
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "5");
        assertNotNull(auth.newProcessor("https://idp.example.com/jwks"),
                "a too-small cache lifetime must degrade to the floor, never to a broken processor");
    }

    @Test
    public void testSubFloorJwksCacheSecondsDoesNotDisableAuthentication() {
        // Deliberately NOT enforced through isUsable(): a false there makes
        // McpApiManager#getAuthenticator fall back to NoneAuthenticator, i.e. anonymous access to
        // /mcp. Turning a bad cache TTL into an authentication BYPASS would be far worse than the
        // 401 it replaces, so the clamp must leave usability untouched.
        final TestAuthenticator auth = usableConfig();
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "1");
        assertTrue(auth.isUsable(), "a sub-floor jwks cache lifetime must never make oauth mode fall back to anonymous access");
    }

    @Test
    public void testBlankIssuerAndJwksUriAreTheRealDefaults() {
        final TestAuthenticator auth = new TestAuthenticator();
        assertEquals("", auth.getIssuer());
        assertEquals("", auth.getJwksUri());
        assertFalse(auth.isUsable());
    }

    // ------------------------------------------------------------------
    // The JWKS source behind getProcessor(): inspected through nimbus's OWN public accessors.
    //
    // Every helper below walks the JWKSetSource decorator chain JWKSourceBuilder assembled, using
    // only public API (getJWSKeySelector / getJWKSource / getJWKSetSource / getSource /
    // getJWKSetURL / getResourceRetriever / getTimeToLive) -- deliberately no reflection over
    // private fields, so a nimbus upgrade that reshapes the chain makes these tests fail loudly at
    // compile time or on the orElseThrow below, rather than silently stop checking anything.
    // ------------------------------------------------------------------

    /**
     * Walks the {@link JWKSetSource} decorator chain behind {@code processor}, outermost wrapper
     * first, ending at whichever source is not itself a wrapper (in production, the
     * {@link URLBasedJWKSetSource} that owns the JWKS URL and the resource retriever).
     *
     * @param processor a processor built by {@link OAuthResourceServerAuthenticator#newProcessor(String)}
     * @return the chain, never empty
     */
    @SuppressWarnings("unchecked")
    private static List<JWKSetSource<SecurityContext>> jwksSourceChain(final ConfigurableJWTProcessor<SecurityContext> processor) {
        final JWSVerificationKeySelector<SecurityContext> selector =
                (JWSVerificationKeySelector<SecurityContext>) processor.getJWSKeySelector();
        final JWKSetBasedJWKSource<SecurityContext> jwkSource = (JWKSetBasedJWKSource<SecurityContext>) selector.getJWKSource();
        final List<JWKSetSource<SecurityContext>> chain = new ArrayList<>();
        JWKSetSource<SecurityContext> node = jwkSource.getJWKSetSource();
        while (node instanceof JWKSetSourceWrapper && !(node instanceof URLBasedJWKSetSource)) {
            chain.add(node);
            node = ((JWKSetSourceWrapper<SecurityContext>) node).getSource();
        }
        chain.add(node);
        return chain;
    }

    /**
     * Finds the single source of {@code type} in {@code processor}'s chain.
     *
     * @param <T> the source type sought
     * @param processor the processor to inspect
     * @param type the source type sought
     * @return the matching source
     * @throws AssertionError if the chain contains no such source
     */
    private static <T> T jwksSourceOfType(final ConfigurableJWTProcessor<SecurityContext> processor, final Class<T> type) {
        return jwksSourceChain(processor).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " is not in the JWKS source chain: "
                        + jwksSourceChain(processor).stream().map(s -> s.getClass().getSimpleName()).toList()));
    }

    /**
     * Returns the JWKS endpoint {@code processor} will actually fetch keys from.
     *
     * @param processor the processor to inspect
     * @return the bound JWKS URL, as a string
     */
    private static String boundJwksUrl(final ConfigurableJWTProcessor<SecurityContext> processor) {
        return jwksSourceOfType(processor, URLBasedJWKSetSource.class).getJWKSetURL().toString();
    }

    /**
     * Returns the key-cache lifetime, in milliseconds, {@code processor} was built with.
     *
     * @param processor the processor to inspect
     * @return the cache time-to-live in milliseconds
     */
    private static long boundCacheTtlMillis(final ConfigurableJWTProcessor<SecurityContext> processor) {
        return jwksSourceOfType(processor, CachingJWKSetSource.class).getTimeToLive();
    }

    /** A jwks.uri-configured double whose real {@code getProcessor()} runs; no network is performed. */
    private static ConfigOnlyAuthenticator jwksConfig(final String jwksUri) {
        final ConfigOnlyAuthenticator auth = new ConfigOnlyAuthenticator();
        auth.properties.put("mcp.oauth.jwks.uri", jwksUri);
        return auth;
    }

    @Test
    public void testEditingJwksUriAtRuntimeRebindsTheProcessorToTheNewUri() {
        // mcp.oauth.jwks.uri is a Fess SYSTEM property: an operator edits it in the admin UI and
        // expects it to take effect, exactly as mcp.oauth.issuer / audience / required.scopes /
        // permission.claim / scope.permission.map all already do (each is re-read per request).
        // Before the config-keyed cache, cachedProcessor was built once and never invalidated, so
        // the processor stayed bound to the OLD URL for the life of the JVM -- every token then
        // 401s as "invalid_token" (nothing about the caller's token is wrong), the real cause
        // reaches the log only at DEBUG, and isUsable() still reports OAUTH because it only tests
        // non-blankness, so not even a state-change log fires. A Fess restart was the only cure.
        final ConfigOnlyAuthenticator auth = jwksConfig("https://old.example.com/jwks");
        final ConfigurableJWTProcessor<SecurityContext> first = auth.getProcessor();
        assertEquals("https://old.example.com/jwks", boundJwksUrl(first), "positive control: the first build honours the config");

        auth.properties.put("mcp.oauth.jwks.uri", "https://new.example.com/jwks");
        final ConfigurableJWTProcessor<SecurityContext> second = auth.getProcessor();

        assertEquals("https://new.example.com/jwks", boundJwksUrl(second),
                "editing mcp.oauth.jwks.uri must rebind the processor; a stale binding 401s every token until Fess restarts");
        assertNotSame(first, second, "a changed JWKS source cannot be served by the processor built for the previous one");
    }

    @Test
    public void testEditingJwksCacheSecondsAtRuntimeRebuildsTheProcessor() {
        // The second half of the same defect, and the easier one to miss: the TTL is read ONLY
        // inside newProcessor(String), so caching the processor froze mcp.oauth.jwks.cache.seconds
        // just as thoroughly as it froze the URI. Both values below clear the 60s floor
        // getJwksCacheSeconds() clamps to, so this test cannot pass by accident through clamping.
        final ConfigOnlyAuthenticator auth = jwksConfig("https://idp.example.com/jwks");
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "600");
        final ConfigurableJWTProcessor<SecurityContext> first = auth.getProcessor();
        assertEquals(600_000L, boundCacheTtlMillis(first), "positive control: the first build honours the configured TTL");

        auth.properties.put("mcp.oauth.jwks.cache.seconds", "900");
        final ConfigurableJWTProcessor<SecurityContext> second = auth.getProcessor();

        assertEquals(900_000L, boundCacheTtlMillis(second), "editing mcp.oauth.jwks.cache.seconds must take effect without a restart");
        assertNotSame(first, second);
    }

    @Test
    public void testUnchangedJwksConfigurationReusesTheCachedProcessor() {
        // The other side of the invalidation fix, and the reason it is keyed on the config rather
        // than simply rebuilt every time: getProcessor() is on the per-request hot path, and
        // rebuilding the source would throw away the whole JWKS key cache -- turning every single
        // request into a fresh network fetch of the key set. The clamped value is what the key
        // compares, so the two sub-floor TTLs below (which getJwksCacheSeconds() folds to the same
        // 60) must NOT count as a change either.
        final ConfigOnlyAuthenticator auth = jwksConfig("https://idp.example.com/jwks");
        final ConfigurableJWTProcessor<SecurityContext> first = auth.getProcessor();
        assertSame(first, auth.getProcessor(), "an unchanged configuration must not rebuild the JWKS source on every request");

        auth.properties.put("mcp.oauth.jwks.cache.seconds", "5");
        final ConfigurableJWTProcessor<SecurityContext> clamped = auth.getProcessor();
        auth.properties.put("mcp.oauth.jwks.cache.seconds", "45");
        assertSame(clamped, auth.getProcessor(), "two sub-floor TTLs that clamp to the same effective value are not a config change");
    }

    @Test
    public void testMalformedJwksUriIsNotCachedSoFixingItRecoversWithoutARestart() {
        // A failed build must leave the cache empty: cachedProcessor is assigned only after
        // newProcessor(String) returns, so the IllegalStateException below cannot be memoised into
        // a permanently-broken instance. Pinned because the config-keyed cache is the natural place
        // to accidentally start caching failures too (e.g. by recording the key before building).
        final ConfigOnlyAuthenticator auth = jwksConfig("not a valid url");
        assertThrows(IllegalStateException.class, auth::getProcessor, "a malformed mcp.oauth.jwks.uri must be reported, not swallowed");
        assertThrows(IllegalStateException.class, auth::getProcessor, "and it must keep being reported, i.e. no negative caching");

        auth.properties.put("mcp.oauth.jwks.uri", "https://idp.example.com/jwks");
        assertEquals("https://idp.example.com/jwks", boundJwksUrl(auth.getProcessor()),
                "correcting the URI must recover on the very next request, with no restart");
    }

    // ------------------------------------------------------------------
    // JWKS retrieval limits: JWKSourceBuilder's own create(URL) defaults are far too tight.
    // ------------------------------------------------------------------

    @Test
    public void testJwksRetrieverOverridesJwkSourceBuilderOwnTightTimeouts() {
        // JWKSourceBuilder.create(URL) supplies its OWN DefaultResourceRetriever, built from
        // JWKSourceBuilder's constants -- 500ms connect, 500ms read, 50KB -- NOT from
        // DefaultResourceRetriever's own no-arg defaults, which are 0/0/0 (unlimited). A 500ms read
        // timeout is easily exceeded by a cold TLS handshake to a real authorization server, and
        // the consequence is not one slow request: the failed fetch trips
        // RateLimitedJWKSetSource's 30s minimum interval, so every token-bearing request 401s as
        // "invalid_token" for the next 30 seconds (permanently, if the endpoint is chronically
        // slower than 500ms) even after the endpoint recovers.
        final ConfigurableJWTProcessor<SecurityContext> processor = jwksConfig("https://idp.example.com/jwks").getProcessor();
        final DefaultResourceRetriever retriever =
                (DefaultResourceRetriever) jwksSourceOfType(processor, URLBasedJWKSetSource.class).getResourceRetriever();

        assertEquals(3_000, retriever.getConnectTimeout(), "500ms is not enough to open a TLS connection to a real AS");
        assertEquals(3_000, retriever.getReadTimeout(), "the 500ms read timeout is the realistic trigger; 30s of 401s follow it");
        assertEquals(262_144, retriever.getSizeLimit(), "a typical JWKS is 2-8KB, but 50KB leaves no room for a large key rotation set");
    }

    @Test
    public void testJwksRetrievalIsRetried() {
        // .retrying(true) inserts a RetryingJWKSetSource, which reattempts a failed fetch once
        // before the failure propagates. Without it a single transient blip is enough to trip the
        // 30s rate-limiter window described above.
        final ConfigurableJWTProcessor<SecurityContext> processor = jwksConfig("https://idp.example.com/jwks").getProcessor();
        assertNotNull(jwksSourceOfType(processor, RetryingJWKSetSource.class),
                "a transient JWKS fetch failure must be retried rather than immediately costing 30s of 401s");
    }

    // ------------------------------------------------------------------
    // Issuer, audience and algorithm checks are EXACT, not merely present.
    // ------------------------------------------------------------------

    @Test
    public void testIssuerThatMerelyStartsWithTheConfiguredIssuerIsRejected() throws Exception {
        // Exact equality, not a prefix relation in either direction.
        // testWrongIssuerIsRejected uses a wholly different issuer, so it survives the naive
        // "tolerate a trailing slash" relaxation iss.startsWith(getIssuer()) -- which accepts
        // a domain the attacker simply registers. The reverse relaxation accepts a truncated
        // issuer. Both are asserted, because either one alone leaves the other open.
        assertIssuerRejected(ISSUER + ".attacker.test");
        assertIssuerRejected(ISSUER + "/evil");
        assertIssuerRejected("https://idp.example");
    }

    private void assertIssuerRejected(final String issuer) throws Exception {
        final String token = sign(validClaims().issuer(issuer), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error =
                assertThrows(McpError.class, () -> auth.authenticate(request, response), "issuer must be rejected: " + issuer);
        assertEquals(401, error.getHttpStatus(), issuer);
    }

    @Test
    public void testAudienceThatOnlySharesAPrefixWithTheCanonicalUriIsRejected() throws Exception {
        // RFC 8707 audience binding is set membership, not string containment.
        // testTokenMintedForAnotherResourceIsRejected uses a wholly different aud, so it survives
        // any prefix-tolerant relaxation of requireAudience; these two values do not.
        assertAudienceRejected(AUDIENCE + "-evil");
        assertAudienceRejected("https://fess.example.com");
    }

    private void assertAudienceRejected(final String audience) throws Exception {
        final String token = sign(validClaims().audience(audience), signingKey);
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest(token);
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error =
                assertThrows(McpError.class, () -> auth.authenticate(request, response), "audience must be rejected: " + audience);
        assertEquals(401, error.getHttpStatus(), audience);
    }

    @Test
    public void testHmacSignedTokenIsRejectedEvenWhenTheJwksPublishesASymmetricKey() throws Exception {
        // The RS256-only JWSVerificationKeySelector is the ONLY thing that rejects this token.
        // With an RSA-only JWKS an HS256 token already fails for an unrelated reason -- nimbus's
        // JWKMatcher demands a kty:oct key for a MAC algorithm and finds none -- so the classic
        // "sign with the RSA public key as the HMAC secret" test would pass even with HS256 added
        // to the selector, and would pin nothing. Publishing an oct key (which an authorization
        // server may legitimately do for a different client) removes that accidental protection
        // and leaves the algorithm restriction itself as the only defence.
        final OctetSequenceKey macKey = new OctetSequenceKeyGenerator(256).keyID("mac-key-1").generate();
        final SignedJWT jwt =
                new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(macKey.getKeyID()).build(), validClaims().build());
        jwt.sign(new MACSigner(macKey.toByteArray()));

        final TestAuthenticator auth = newAuthenticator("");
        auth.extraVerificationKey = macKey;
        final MockletHttpServletRequestImpl request = bearerRequest(jwt.serialize());
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));
        assertEquals(401, error.getHttpStatus(), "a MAC-signed token must be refused by the RS256-only key selector");
    }

    // ------------------------------------------------------------------
    // Scope claim: RFC 9068's "scope" and Entra ID / Okta's "scp".
    // ------------------------------------------------------------------

    @Test
    public void testScopesAreReadFromTheRfc9068ScopeClaim() throws Exception {
        final McpPrincipal principal = authenticate(sign(validClaims().claim("scope", "fess:search fess:admin"), signingKey));
        assertEquals(Set.of("fess:search", "fess:admin"), principal.getScopes(),
                "the RFC 9068 space-delimited scope claim must keep working exactly as before");
    }

    @Test
    public void testScopesFallBackToTheScpClaimAsAStringForEntraId() throws Exception {
        // Microsoft Entra ID emits scp, never scope -- it is in neither the access-token-claims
        // reference nor the optional-claims reference, so an operator cannot even enable it.
        // Reading only "scope" resolved an empty set for every Entra-issued token, which either
        // refuses every caller with insufficient_scope (required.scopes set) or silently drops
        // them to the guest roles (required.scopes unset).
        final McpPrincipal principal = authenticate(sign(validClaims().claim("scp", "fess:search fess:admin"), signingKey));
        assertEquals(Set.of("fess:search", "fess:admin"), principal.getScopes(),
                "an Entra ID access token carries its scopes in scp as a space-delimited string");
    }

    @Test
    public void testScopesFallBackToTheScpClaimAsAnArrayForOkta() throws Exception {
        // Okta also uses scp, but as a JSON array -- so handling only the RFC's string shape
        // would fix Entra ID and leave Okta broken in exactly the same way.
        final McpPrincipal principal = authenticate(sign(validClaims().claim("scp", List.of("fess:search", "fess:admin")), signingKey));
        assertEquals(Set.of("fess:search", "fess:admin"), principal.getScopes(),
                "an Okta access token carries its scopes in scp as a JSON array");
    }

    @Test
    public void testScopeClaimWinsOverScpWhenBothArePresent() throws Exception {
        final McpPrincipal principal = authenticate(sign(validClaims().claim("scope", "from-scope").claim("scp", "from-scp"), signingKey));
        assertEquals(Set.of("from-scope"), principal.getScopes(), "a token carrying both must keep its standards-defined claim");
    }

    @Test
    public void testRequiredScopesAreSatisfiedByAnScpOnlyToken() {
        // The observable half: the fallback is not just parsed, it actually satisfies the gate
        // that would otherwise refuse every Entra ID and Okta caller with insufficient_scope.
        final TestAuthenticator auth = newAuthenticator("fess:search");
        assertDoesNotThrow(() -> {
            final MockletHttpServletRequestImpl request = bearerRequest(sign(validClaims().claim("scp", "fess:search"), signingKey));
            auth.authenticate(request, McpHttpTestSupport.newResponse(request));
        }, "mcp.oauth.required.scopes must be satisfiable by a token that carries scp rather than scope");
    }

    // ------------------------------------------------------------------
    // A JWKS outage is a server-side failure, not a bad credential.
    // ------------------------------------------------------------------

    /**
     * {@link ConfigOnlyAuthenticator} whose JWKS source always fails the way an unreachable
     * endpoint does. {@code RemoteKeySourceException} is what nimbus raises for DNS failures, TLS
     * failures, connect/read timeouts and non-200 responses alike, so one throw covers the whole
     * class of transport failures.
     */
    static class UnreachableJwksAuthenticator extends ConfigOnlyAuthenticator {
        @Override
        protected ConfigurableJWTProcessor<SecurityContext> getProcessor() {
            return newProcessor((jwkSelector, context) -> {
                throw new RemoteKeySourceException("Couldn't retrieve remote JWK set", new SocketTimeoutException("Read timed out"));
            });
        }
    }

    @Test
    public void testJwksTransportFailureIsReportedAsUnavailableNotAsAnInvalidToken() throws Exception {
        // RemoteKeySourceException extends KeySourceException extends JOSEException, so before the
        // dedicated catch arm this landed in the same `catch (... | JOSEException | ...)` as a
        // genuinely malformed token and became 401 invalid_token. That tells a perfectly healthy
        // client its credential is bad -- so it discards a valid token and fetches another one,
        // which cannot help, because the token was never the problem. The condition also does not
        // self-heal: a failed build is deliberately not cached, so every following request fails
        // the same way until the IdP comes back.
        final UnreachableJwksAuthenticator auth = new UnreachableJwksAuthenticator();
        auth.properties.put("mcp.oauth.issuer", ISSUER);
        auth.properties.put("mcp.oauth.audience", AUDIENCE);
        final MockletHttpServletRequestImpl request = bearerRequest(sign(validClaims(), signingKey));
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));

        assertEquals(503, error.getHttpStatus(), "an unreachable JWKS endpoint is a server-side outage, not a bad credential");
        assertFalse(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("token"),
                "the message must not blame the caller's token: " + error.getMessage());
        assertNull(response.getHeader("WWW-Authenticate"), "503 is not an authentication challenge, so it must not carry one");
    }

    @Test
    public void testMalformedJwksUriIsReportedAsUnavailableNotAsAnInvalidToken() {
        // getProcessor() wraps newProcessor's MalformedURLException in an IllegalStateException.
        // That is a RuntimeException, so it too used to be laundered into 401 invalid_token --
        // permanently, for every caller, with the real cause visible only at DEBUG. isUsable()
        // cannot catch this case: it only checks that the value is non-blank, and deliberately so
        // (a stricter isUsable() falls back to NoneAuthenticator, i.e. anonymous access).
        final ConfigOnlyAuthenticator auth = new ConfigOnlyAuthenticator();
        auth.properties.put("mcp.oauth.issuer", ISSUER);
        auth.properties.put("mcp.oauth.audience", AUDIENCE);
        auth.properties.put("mcp.oauth.jwks.uri", "htp://idp.example.com/jwks");
        final MockletHttpServletRequestImpl request = bearerRequest("any.token.value");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));

        assertEquals(503, error.getHttpStatus(), "a misconfigured jwks.uri is the operator's fault, not the caller's");
        assertNull(response.getHeader("WWW-Authenticate"), "503 is not an authentication challenge, so it must not carry one");
    }

    @Test
    public void testAMalformedTokenIsStillAnInvalidTokenNotAnOutage() {
        // Guards the other direction: the new 503 arms must not swallow the caller-directed case.
        // If this ever returns 503, the catch arms have been widened too far and a bad credential
        // is being reported as a server outage.
        final TestAuthenticator auth = newAuthenticator("");
        final MockletHttpServletRequestImpl request = bearerRequest("not-a-jwt");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> auth.authenticate(request, response));

        assertEquals(401, error.getHttpStatus(), "a malformed bearer token is still the caller's problem");
        assertTrue(response.getHeader("WWW-Authenticate").contains("invalid_token"),
                "a bad credential must still get the RFC 6750 invalid_token challenge");
    }
}
