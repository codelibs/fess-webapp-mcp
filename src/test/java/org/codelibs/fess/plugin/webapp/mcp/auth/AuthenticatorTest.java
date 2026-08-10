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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codelibs.fess.app.service.AccessTokenService;
import org.codelibs.fess.exception.InvalidAccessTokenException;
import org.codelibs.fess.plugin.webapp.api.mcp.McpApiManager;
import org.codelibs.fess.plugin.webapp.api.mcp.McpHttpTestSupport;
import org.codelibs.fess.plugin.webapp.mcp.McpConstants;
import org.codelibs.fess.plugin.webapp.mcp.handler.McpMethodHandler;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpDispatcher;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpError;
import org.dbflute.optional.OptionalEntity;
import org.dbflute.utflute.mocklet.MockletHttpServletRequestImpl;
import org.dbflute.utflute.mocklet.MockletHttpServletResponseImpl;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tests for the authentication skeleton: {@link McpPrincipal}, {@link NoneAuthenticator},
 * {@link FessTokenAuthenticator}, and {@code McpApiManager#authenticate}'s wiring of them.
 *
 * <p>
 * {@link #testNoneModeDoesNotSeedUserRoles()} is the regression guard for the whole hazard this
 * task exists to avoid: seeding {@code userRoles} unconditionally would short-circuit Fess's
 * {@code RoleQueryHelper} before it ever consults an existing {@code Authorization: Bearer
 * <fess-token>} caller's real permissions, silently dropping such callers to guest.
 * </p>
 */
public class AuthenticatorTest {

    /** Test double: never touches {@code ComponentUtil}, mirroring McpApiManagerHttpTest's TestManager. */
    static class TestManager extends McpApiManager {
        String body = "";
        String authMode = "none";
        List<String> guestRoles = List.of("Rguest", "1guest");
        List<String> defaultPermissions = List.of();
        boolean getSearchGuestRoleListCalled = false;
        boolean getSearchDefaultPermissionListCalled = false;

        @Override
        protected String readRequestBody(final HttpServletRequest request) throws IOException {
            return body;
        }

        @Override
        protected void writeHeaders(final HttpServletResponse response) {
            // no-op: the real implementation reads api.json.response.headers from the container
        }

        @Override
        protected boolean isEnabled() {
            // no-op: the real implementation reads mcp.enabled from the container. Without this
            // override, process() would throw resolving ComponentUtil before authenticate() ever
            // ran, which would make every userRoles assertion in this file vacuously true.
            return true;
        }

        @Override
        protected int getRequestMaxBytes() {
            return 1_048_576;
        }

        @Override
        protected String getAuthMode() {
            // no-op: the real implementation reads mcp.auth.mode from the container
            return authMode;
        }

        @Override
        protected List<String> getSearchGuestRoleList() {
            // no-op: the real implementation reads role.search.guest.permissions from the
            // container via FessConfig#getSearchGuestRoleList(). Records whether it ran at all,
            // so tests can assert resolveRoles only consults it when permissions are empty.
            getSearchGuestRoleListCalled = true;
            return guestRoles;
        }

        @Override
        protected List<String> getSearchDefaultPermissionList() {
            // no-op: the real implementation reads role.search.default.permissions from the
            // container. Records whether it ran at all.
            getSearchDefaultPermissionListCalled = true;
            return defaultPermissions;
        }

        /**
         * Exposes the protected {@code getAuthenticator()} seam to this test file, which lives
         * in a different package from {@code McpApiManager} and so cannot call an inherited
         * protected method on a {@code TestManager}-typed reference directly.
         */
        McpAuthenticator resolveAuthenticator() {
            return getAuthenticator();
        }

        /**
         * Exposes the protected {@code resolveRateLimitKey(...)} seam, for the same reason as
         * {@link #resolveAuthenticator()}.
         */
        String resolveRateLimitKeyFor(final HttpServletRequest request, final McpCallContext context) {
            return resolveRateLimitKey(request, context);
        }
    }

    /**
     * Test double for {@link #testGetAuthModeRealBodyDefaultsToNoneWhenPropertyUnset}
     * specifically: unlike {@link TestManager}, this class does <em>not</em> override
     * {@code getAuthMode()} itself. Overriding only {@link #getSystemProperty(String, String)}
     * -- the one primitive {@code getAuthMode()}'s real body touches {@code ComponentUtil}
     * through -- lets that real body run container-free, so its literal key and default-value
     * argument are actually exercised instead of permanently bypassed.
     */
    static class SystemPropertyCapturingManager extends McpApiManager {
        String capturedKey;
        String capturedDefaultValue;

        @Override
        protected String getSystemProperty(final String key, final String defaultValue) {
            // Simulates an unset property: real FessConfig#getSystemProperty returns
            // defaultValue precisely when the key is unset, so echoing it back here is a
            // faithful stand-in without needing a live container.
            capturedKey = key;
            capturedDefaultValue = defaultValue;
            return defaultValue;
        }

        /** Exposes the protected {@code getAuthMode()} seam, for the same reason as {@code TestManager#resolveAuthenticator()}. */
        String resolveAuthMode() {
            return getAuthMode();
        }

        /** Exposes the protected {@code getAuthenticator()} seam, for the same reason as above. */
        McpAuthenticator resolveAuthenticator() {
            return getAuthenticator();
        }
    }

    private void post(final TestManager manager, final String body, final Map<String, String> headers) throws Exception {
        manager.body = body;
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        headers.forEach(request::addHeader);
        manager.process(request, McpHttpTestSupport.newResponse(request), null);
    }

    private Map<String, String> modernHeaders(final String method) {
        return Map.of(McpConstants.HEADER_PROTOCOL_VERSION, "2026-07-28", McpConstants.HEADER_METHOD, method);
    }

    private String modernBody(final String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"," + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
    }

    // ------------------------------------------------------------------
    // The hazard: none mode must never seed userRoles.
    // ------------------------------------------------------------------

    @Test
    public void testNoneModeDoesNotSeedUserRoles() throws Exception {
        final TestManager manager = new TestManager();
        manager.authMode = "none";
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer legacy-fess-token");
        manager.process(request, McpHttpTestSupport.newResponse(request), null);

        assertNull(request.getAttribute(McpConstants.USER_ROLES_ATTRIBUTE),
                "seeding it would short-circuit RoleQueryHelper before processAccessToken and "
                        + "silently drop existing Fess access-token deployments to guest");
    }

    @Test
    public void testFessTokenModeSeedsUserRoles() throws Exception {
        // Positive control for the hazard guard above: proves USER_ROLES_ATTRIBUTE is not
        // simply always null, only when the active authenticator does not own role resolution.
        final TestManager manager = new TestManager() {
            @Override
            protected McpAuthenticator getAuthenticator() {
                return new McpAuthenticator() {
                    @Override
                    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
                        return new McpPrincipal("caller", Set.of(), Set.of("Rfoo"));
                    }

                    @Override
                    public boolean ownsRoleResolution() {
                        return true;
                    }
                };
            }
        };
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        manager.process(request, McpHttpTestSupport.newResponse(request), null);

        assertNotNull(request.getAttribute(McpConstants.USER_ROLES_ATTRIBUTE));
    }

    @Test
    public void testNoneModeNeverConsultsGuestOrDefaultPermissionLists() throws Exception {
        // "authentication should cost nothing" in none mode: resolveRoles (and therefore the
        // two FessConfig-backed lookups it makes) must never run at all, not merely return
        // something unused.
        final TestManager manager = new TestManager();
        manager.authMode = "none";
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));

        assertFalse(manager.getSearchGuestRoleListCalled, "getSearchGuestRoleList() must not run in none mode");
        assertFalse(manager.getSearchDefaultPermissionListCalled, "getSearchDefaultPermissionList() must not run in none mode");
    }

    @Test
    public void testResolvedPrincipalIsPassedToCallContext() throws Exception {
        // McpCallContext must actually carry the resolved principal through to the dispatcher --
        // the whole reason Task 13 (the index_stats gate) can consume it. Deliberately uses a
        // non-anonymous principal: with the anonymous singleton, a mutant that dropped the
        // principal argument entirely (defaulting McpCallContext back to anonymous()) would be
        // indistinguishable from correct code in none mode, so this would not actually be
        // falsifiable against that mutant.
        final McpPrincipal resolved = new McpPrincipal("caller", Set.of(), Set.of("Rfoo"));
        final McpPrincipal[] captured = new McpPrincipal[1];
        final TestManager manager = new TestManager() {
            @Override
            protected McpAuthenticator getAuthenticator() {
                return new McpAuthenticator() {
                    @Override
                    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
                        return resolved;
                    }

                    @Override
                    public boolean ownsRoleResolution() {
                        return false;
                    }
                };
            }

            @Override
            protected McpDispatcher getDispatcher() {
                return new McpDispatcher(List.of(new McpMethodHandler() {
                    @Override
                    public String getMethod() {
                        return "tools/list";
                    }

                    @Override
                    public Map<String, Object> handle(final McpCallContext context) {
                        captured[0] = context.getPrincipal();
                        return new LinkedHashMap<>(Map.of("tools", List.of()));
                    }
                }));
            }
        };
        post(manager, modernBody("tools/list"), modernHeaders("tools/list"));

        assertSame(resolved, captured[0], "the principal authenticate() resolved must reach the dispatched McpCallContext");
    }

    // ------------------------------------------------------------------
    // resolvePrincipalSubject: rate-limiting keys on the resolved principal's subject.
    // ------------------------------------------------------------------

    @Test
    public void testResolveRateLimitKeyUsesPrincipalSubjectWhenPresent() {
        final TestManager manager = new TestManager();
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setRemoteAddr("203.0.113.5");
        final McpCallContext context = new McpCallContext(null, null, Map.of(), new McpPrincipal("stable-subject", Set.of(), Set.of()));

        assertEquals("stable-subject", manager.resolveRateLimitKeyFor(request, context),
                "an authenticated caller must be rate-limited per-subject, not per-IP -- otherwise callers behind one NAT "
                        + "would share a bucket, and one token used from several source IPs would get one bucket each");
    }

    @Test
    public void testResolveRateLimitKeyFallsBackToRemoteAddrForAnonymousPrincipal() {
        final TestManager manager = new TestManager();
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.setRemoteAddr("203.0.113.5");
        final McpCallContext context = new McpCallContext(null, null, Map.of(), McpPrincipal.anonymous());

        assertEquals("203.0.113.5", manager.resolveRateLimitKeyFor(request, context),
                "none mode's anonymous principal has no subject, so this must fall back to the caller's IP exactly as before "
                        + "this task wired a principal into McpCallContext at all");
    }

    // ------------------------------------------------------------------
    // Compensation: role.search.default.permissions and the guest-role fallback.
    // ------------------------------------------------------------------

    @Test
    public void testSeededRolesIncludeDefaultPermissionsAndSkipGuestWhenPermissionsPresent() throws Exception {
        final TestManager manager = new TestManager() {
            @Override
            protected McpAuthenticator getAuthenticator() {
                return new McpAuthenticator() {
                    @Override
                    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
                        return new McpPrincipal("caller", Set.of(), Set.of("Rfoo"));
                    }

                    @Override
                    public boolean ownsRoleResolution() {
                        return true;
                    }
                };
            }
        };
        manager.defaultPermissions = List.of("Rall");
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        manager.process(request, McpHttpTestSupport.newResponse(request), null);

        @SuppressWarnings("unchecked")
        final Set<String> roles = (Set<String>) request.getAttribute(McpConstants.USER_ROLES_ATTRIBUTE);
        assertEquals(Set.of("Rfoo", "Rall"), roles);
        assertFalse(manager.getSearchGuestRoleListCalled, "a caller with real permissions must not fall back to guest");
        assertTrue(manager.getSearchDefaultPermissionListCalled, "role.search.default.permissions must always be added when seeding");
    }

    @Test
    public void testSeededRolesFallBackToGuestWhenPermissionsAreEmpty() throws Exception {
        final TestManager manager = new TestManager() {
            @Override
            protected McpAuthenticator getAuthenticator() {
                return new McpAuthenticator() {
                    @Override
                    public McpPrincipal authenticate(final HttpServletRequest request, final HttpServletResponse response) {
                        return new McpPrincipal("caller", Set.of(), Set.of());
                    }

                    @Override
                    public boolean ownsRoleResolution() {
                        return true;
                    }
                };
            }
        };
        manager.guestRoles = List.of("Rguest", "1guest");
        manager.defaultPermissions = List.of("Rall");
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        manager.process(request, McpHttpTestSupport.newResponse(request), null);

        @SuppressWarnings("unchecked")
        final Set<String> roles = (Set<String>) request.getAttribute(McpConstants.USER_ROLES_ATTRIBUTE);
        assertEquals(Set.of("Rguest", "1guest", "Rall"), roles);
        assertTrue(manager.getSearchGuestRoleListCalled);
    }

    // ------------------------------------------------------------------
    // McpAuthenticator selection.
    // ------------------------------------------------------------------

    @Test
    public void testFessTokenAuthModeSelectsFessTokenAuthenticator() {
        final TestManager manager = new TestManager();
        manager.authMode = "fess_token";
        assertTrue(manager.resolveAuthenticator() instanceof FessTokenAuthenticator);
    }

    @Test
    public void testUnrecognizedAuthModeFallsBackToNone() {
        // Matches the design's own fallback rule for the not-yet-implemented oauth mode
        // (§8.3: an unusable auth mode falls back to none rather than failing closed or open
        // in some other way).
        final TestManager manager = new TestManager();
        manager.authMode = "oauth";
        assertTrue(manager.resolveAuthenticator() instanceof NoneAuthenticator);
    }

    @Test
    public void testDefaultAuthModeConstantIsNone() {
        // McpApiManager.AUTH_MODE_NONE is the literal getAuthMode()'s real (non-overridden) body
        // falls back to. Every test double in this file overrides getAuthMode() -- it reads
        // ComponentUtil -- so this is the only place that literal is exercised at all; without
        // it, changing McpApiManager's default from "none" to anything else would leave every
        // test in this file green while silently rejecting every existing unauthenticated
        // deployment on upgrade.
        assertEquals("none", McpApiManager.AUTH_MODE_NONE);
    }

    @Test
    public void testDefaultAuthModeConstantResolvesToNoneAuthenticator() {
        // Non-tautological companion to the above: routes McpApiManager's own default-mode
        // constant (not an independently-typed literal) through getAuthenticator()'s real
        // selection logic.
        final TestManager manager = new TestManager();
        manager.authMode = McpApiManager.AUTH_MODE_NONE;
        assertTrue(manager.resolveAuthenticator() instanceof NoneAuthenticator);
    }

    @Test
    public void testGetAuthModeRealBodyDefaultsToNoneWhenPropertyUnset() {
        // The two tests above still don't touch the production fallback EXPRESSION --
        // getSystemProperty("mcp.auth.mode", AUTH_MODE_NONE) -- because getAuthMode() itself is
        // always overridden elsewhere in this file. This test overrides only the ComponentUtil-
        // touching primitive one level below, so getAuthMode()'s real body actually runs: a
        // regression that passes a different literal as either argument at that call site would
        // survive every other test in this suite and flip the default every unauthenticated
        // deployment relies on.
        final SystemPropertyCapturingManager manager = new SystemPropertyCapturingManager();

        final String authMode = manager.resolveAuthMode();

        assertEquals("mcp.auth.mode", manager.capturedKey, "getAuthMode() must read this exact property key");
        assertEquals(McpApiManager.AUTH_MODE_NONE, manager.capturedDefaultValue,
                "getAuthMode() must pass AUTH_MODE_NONE as the default, not a different or re-typed literal");
        assertEquals("none", authMode);
        assertTrue(manager.resolveAuthenticator() instanceof NoneAuthenticator);
    }

    // ------------------------------------------------------------------
    // McpPrincipal.
    // ------------------------------------------------------------------

    @Test
    public void testAnonymousPrincipalIsAConstant() {
        assertSame(McpPrincipal.anonymous(), McpPrincipal.anonymous(), "anonymous() must not allocate a fresh instance per call");
        assertNull(McpPrincipal.anonymous().getSubject());
        assertTrue(McpPrincipal.anonymous().getScopes().isEmpty());
        assertTrue(McpPrincipal.anonymous().getPermissions().isEmpty());
    }

    // ------------------------------------------------------------------
    // NoneAuthenticator.
    // ------------------------------------------------------------------

    @Test
    public void testNoneAuthenticatorAlwaysReturnsAnonymousAndNeverOwnsRoleResolution() {
        final NoneAuthenticator authenticator = new NoneAuthenticator();
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        assertSame(McpPrincipal.anonymous(), authenticator.authenticate(request, response));
        assertFalse(authenticator.ownsRoleResolution());
    }

    // ------------------------------------------------------------------
    // FessTokenAuthenticator: bearer extraction (RFC 6750 case-insensitivity).
    // ------------------------------------------------------------------

    @Test
    public void testBearerSchemeIsCaseInsensitive() {
        // RFC 6750 requires case-insensitive scheme matching. Fess's AccessTokenHelper
        // compares with String.equals and throws on "bearer".
        assertEquals("abc", FessTokenAuthenticator.extractBearerToken("bearer abc"));
        assertEquals("abc", FessTokenAuthenticator.extractBearerToken("BEARER abc"));
        assertEquals("abc", FessTokenAuthenticator.extractBearerToken("Bearer abc"));
        assertNull(FessTokenAuthenticator.extractBearerToken("Basic abc"));
        assertNull(FessTokenAuthenticator.extractBearerToken(null));
    }

    @Test
    public void testExtractBearerTokenEdgeCases() {
        assertNull(FessTokenAuthenticator.extractBearerToken("Bearer"), "no token after the scheme");
        assertNull(FessTokenAuthenticator.extractBearerToken("Bearer   "), "whitespace-only token");
        assertEquals("abc", FessTokenAuthenticator.extractBearerToken("Bearer   abc"), "extra internal whitespace is trimmed");
        assertEquals("abc", FessTokenAuthenticator.extractBearerToken("  Bearer abc  "), "surrounding whitespace is trimmed");
    }

    // ------------------------------------------------------------------
    // FessTokenAuthenticator: authenticate().
    // ------------------------------------------------------------------

    @Test
    public void testFessTokenModeRejectsAMissingToken() {
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        final McpError error = assertThrows(McpError.class, () -> new FessTokenAuthenticator().authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
        assertNotNull(response.getHeader("WWW-Authenticate"));
    }

    @Test
    public void testFessTokenModeRejectsAnInvalidToken() {
        // resolvePermissions is the ComponentUtil-backed seam; stubbing it out here proves
        // authenticate() converts a thrown InvalidAccessTokenException into a 401 + challenge
        // rather than letting it escape as an unhandled RuntimeException.
        final FessTokenAuthenticator authenticator = new FessTokenAuthenticator() {
            @Override
            protected Set<String> resolvePermissions(final HttpServletRequest request, final String token) {
                throw new InvalidAccessTokenException("invalid_token", "Invalid token: " + token);
            }
        };
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer bogus");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);

        final McpError error = assertThrows(McpError.class, () -> authenticator.authenticate(request, response));
        assertEquals(401, error.getHttpStatus());
        assertNotNull(response.getHeader("WWW-Authenticate"));
    }

    @Test
    public void testFessTokenModeResolvesPermissionsFromToken() {
        final FessTokenAuthenticator authenticator = new FessTokenAuthenticator() {
            @Override
            protected Set<String> resolvePermissions(final HttpServletRequest request, final String token) {
                assertEquals("abc123", token);
                return new HashSet<>(Set.of("Rfoo"));
            }
        };
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer abc123");

        final McpPrincipal principal = authenticator.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertEquals(Set.of("Rfoo"), principal.getPermissions());
        assertNotNull(principal.getSubject());
        assertTrue(authenticator.ownsRoleResolution());
    }

    @Test
    public void testLowercaseBearerSchemeAuthenticatesSuccessfully() {
        // The integration-level guard for trap #1: if authenticate() called
        // AccessTokenHelper.getAccessTokenFromRequest for extraction (or fed the original,
        // lowercase-scheme header straight to AccessTokenService), this would throw
        // InvalidAccessTokenException instead of succeeding.
        final FessTokenAuthenticator authenticator = new FessTokenAuthenticator() {
            @Override
            protected Set<String> resolvePermissions(final HttpServletRequest request, final String token) {
                return Set.of("Rfoo");
            }
        };
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Authorization", "bearer abc123");

        final McpPrincipal principal = authenticator.authenticate(request, McpHttpTestSupport.newResponse(request));
        assertEquals(Set.of("Rfoo"), principal.getPermissions());
    }

    @Test
    public void testFessTokenModeDoesNotTouchDiContainerWhenTokenIsMissing() {
        // The base (non-overridden) FessTokenAuthenticator's resolvePermissions touches
        // ComponentUtil; this test never overrides it, yet still succeeds container-free,
        // because the missing-token check must reject before resolvePermissions is ever called.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        final MockletHttpServletResponseImpl response = McpHttpTestSupport.newResponse(request);
        assertThrows(McpError.class, () -> new FessTokenAuthenticator().authenticate(request, response));
    }

    // ------------------------------------------------------------------
    // FessTokenAuthenticator: the query-derived permission channel is never adopted.
    // ------------------------------------------------------------------

    @Test
    public void testQueryPermissionsSuppressedRequestNeverReportsQueryParameters() {
        // Direct proof of the second trap called out in this task: AccessTokenService folds
        // request.getParameterValues(accessToken.getParameterName()) into the resolved
        // permission set. This wrapper is what stands between that call and the real request,
        // so it alone is responsible for the channel never contributing anything -- tested here
        // without needing the live AccessTokenService/DI container resolvePermissions requires.
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addParameter("permission", "Radmin-api");
        request.addParameter("anything", "also-injected");

        final FessTokenAuthenticator.QueryPermissionsSuppressedRequest wrapped =
                new FessTokenAuthenticator.QueryPermissionsSuppressedRequest(request, "abc123");

        assertNull(wrapped.getParameterValues("permission"), "the token row's declared parameterName must report nothing supplied");
        assertNull(wrapped.getParameterValues("anything"));
        assertEquals("Bearer abc123", wrapped.getHeader("Authorization"), "must report a canonically-cased bearer credential");
        assertEquals("Bearer abc123", wrapped.getHeader("authorization"), "header lookup must stay case-insensitive by name too");
    }

    @Test
    public void testResolvePermissionsCallsAccessTokenServiceWithTheSuppressingWrapperNotTheRawRequest() {
        // Binds the wrapper (proven in isolation above) to the actual call site: nothing
        // previously asserted that resolvePermissions hands AccessTokenService the wrapper
        // rather than the raw request. If it passed the raw request instead, this test would
        // observe the raw MockletHttpServletRequestImpl here and the query-injected "permission"
        // parameter would come back non-null -- the exact escalation channel this class exists
        // to close.
        final HttpServletRequest[] captured = new HttpServletRequest[1];
        final FessTokenAuthenticator authenticator = new FessTokenAuthenticator() {
            @Override
            protected AccessTokenService getAccessTokenService() {
                return new AccessTokenService() {
                    @Override
                    public OptionalEntity<Set<String>> getPermissions(final HttpServletRequest request) {
                        captured[0] = request;
                        return OptionalEntity.of(Set.of("Rfoo"));
                    }
                };
            }
        };
        final MockletHttpServletRequestImpl request = McpHttpTestSupport.newRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer abc123");
        request.addParameter("permission", "Radmin-api");

        final McpPrincipal principal = authenticator.authenticate(request, McpHttpTestSupport.newResponse(request));

        assertEquals(Set.of("Rfoo"), principal.getPermissions());
        assertNotNull(captured[0], "resolvePermissions must call AccessTokenService#getPermissions at all");
        assertTrue(captured[0] instanceof FessTokenAuthenticator.QueryPermissionsSuppressedRequest,
                "resolvePermissions must pass the parameter-suppressing wrapper, not the raw request: was " + captured[0].getClass());
        assertNull(captured[0].getParameterValues("permission"),
                "the request AccessTokenService actually receives must not expose the query-injected permission");
    }
}
