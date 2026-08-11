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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
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
     * {@code getAuthMode()}. {@link #getProcessor()} is the one deliberate exception: it is the
     * seam that would otherwise reach a real JWKS URL over HTTP. Even there, it delegates to the
     * real {@link OAuthResourceServerAuthenticator#newProcessor(JWKSource)} with an in-memory
     * key source, rather than re-implementing that assembly here -- a hand-maintained duplicate
     * would verify only itself, never the production wiring (key selector, RS256 restriction,
     * claims verifier) that actually ships.
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
            final JWKSource<SecurityContext> inMemorySource =
                    new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(verificationKey.toPublicJWK()));
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
}
