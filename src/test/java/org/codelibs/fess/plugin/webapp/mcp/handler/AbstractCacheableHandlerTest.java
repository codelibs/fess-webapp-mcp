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
package org.codelibs.fess.plugin.webapp.mcp.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.plugin.webapp.api.mcp.McpApiManager;
import org.codelibs.fess.plugin.webapp.mcp.protocol.McpCallContext;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link AbstractCacheableHandler}'s real {@code getTtlMs()} body.
 *
 * <p>
 * Every subclass test suite (DiscoverHandlerTest, ToolsListHandlerTest, …) overrides
 * {@code getTtlMs()} directly to fix the TTL for its own assertions, which means the shipped
 * config-reading body -- {@code getSystemProperty}, {@code trim}, {@code Long.parseLong}, catch
 * {@code NumberFormatException}, fall back to the default -- was never actually executed by any
 * test. This class exercises it directly through a minimal concrete subclass with a stubbed
 * {@link FessConfig}, so it never touches the DI container.
 * </p>
 */
public class AbstractCacheableHandlerTest {

    private static final String TTL_KEY = "mcp.cache.test.ttl.ms";

    /** Minimal concrete subclass that does not override {@code getTtlMs()}. */
    private static final class TestHandler extends AbstractCacheableHandler {

        private final String configuredValue;

        TestHandler(final String configuredValue, final long defaultTtlMs) {
            super(TTL_KEY, defaultTtlMs);
            this.configuredValue = configuredValue;
        }

        @Override
        public String getMethod() {
            return "test/cacheable";
        }

        @Override
        public Map<String, Object> handle(final McpCallContext context) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        protected String getCacheScope(final McpCallContext context) {
            return "public";
        }

        @Override
        protected FessConfig getFessConfig() {
            return new FessConfig.SimpleImpl() {

                private static final long serialVersionUID = 1L;

                @Override
                public String getSystemProperty(final String key) {
                    assertEquals(TTL_KEY, key, "must read this handler's own configured key, not some other one");
                    return configuredValue;
                }
            };
        }
    }

    @Test
    public void testUnparseableValueFallsBackToTheDefault() {
        // If the NumberFormatException catch were removed, Long.parseLong("abc") would throw
        // instead of this returning the default.
        final TestHandler handler = new TestHandler("abc", 3_600_000L);
        assertEquals(3_600_000L, handler.getTtlMs());
    }

    @Test
    public void testMissingValueFallsBackToTheDefault() {
        // If the null check were removed or inverted, this would NPE instead of returning the
        // default.
        final TestHandler handler = new TestHandler(null, 3_600_000L);
        assertEquals(3_600_000L, handler.getTtlMs());
    }

    @Test
    public void testValidValueIsParsedVerbatim() {
        // If parsing were replaced by "always return the default", this would still see
        // 3_600_000L instead of the configured 1234L.
        final TestHandler handler = new TestHandler("1234", 3_600_000L);
        assertEquals(1234L, handler.getTtlMs());
    }

    @Test
    public void testNegativeValueIsParsedAndReturnedUnclamped() {
        // getTtlMs() itself must not clamp -- only resolveTtlMs() does (per its own contract:
        // "does not apply the final >= 0 floor itself"). If getTtlMs() clamped internally, this
        // would see 0 here instead of the raw parsed -1.
        final TestHandler handler = new TestHandler("-1", 3_600_000L);
        assertEquals(-1L, handler.getTtlMs());
        assertEquals(0L, handler.resolveTtlMs(), "resolveTtlMs() is what applies the >= 0 floor");
    }

    @Test
    public void testValueIsTrimmedBeforeParsing() {
        // Config values can carry incidental whitespace; if trim() were removed,
        // Long.parseLong(" 5000 ") would throw and this would see the default instead of 5000.
        final TestHandler handler = new TestHandler(" 5000 ", 3_600_000L);
        assertEquals(5000L, handler.getTtlMs());
    }

    /**
     * Test double for {@link #testGetAuthModeRealBodyReadsTheExactKeyAndDefault} specifically:
     * unlike every {@code getAuthMode()}-overriding double elsewhere in this suite (e.g.
     * {@code ToolsListHandlerTest.FixedTtlHandler}), this class does <em>not</em> override
     * {@code getAuthMode()} itself. Overriding only {@link AbstractCacheableHandler#getSystemProperty}
     * -- the one primitive {@code getAuthMode()}'s real body touches {@code ComponentUtil}
     * through -- lets that real body run container-free, so its literal key and default-value
     * argument are actually exercised instead of permanently bypassed. Mirrors
     * {@code AuthenticatorTest.SystemPropertyCapturingManager} for the identical reason.
     */
    private static final class AuthModeCapturingHandler extends AbstractCacheableHandler {

        String capturedKey;
        String capturedDefaultValue;

        AuthModeCapturingHandler() {
            super(TTL_KEY, 3_600_000L);
        }

        @Override
        public String getMethod() {
            return "test/cacheable";
        }

        @Override
        public Map<String, Object> handle(final McpCallContext context) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        protected String getCacheScope(final McpCallContext context) {
            return "public";
        }

        @Override
        protected String getSystemProperty(final String key, final String defaultValue) {
            // Simulates an unset property: real FessConfig#getSystemProperty returns
            // defaultValue precisely when the key is unset, so echoing it back here is a
            // faithful stand-in without needing a live container.
            capturedKey = key;
            capturedDefaultValue = defaultValue;
            return defaultValue;
        }
    }

    @Test
    public void testGetAuthModeRealBodyReadsTheExactKeyAndDefault() {
        // Every getAuthMode()-overriding test double elsewhere in this suite leaves this
        // method's own body -- the actual key/default it passes to getSystemProperty -- entirely
        // unexercised. A regression that reads a typo'd key (which always misses, silently and
        // permanently falling back to the default) would survive every one of those tests; this
        // one does not, because it overrides only the ComponentUtil-touching primitive one level
        // below and lets getAuthMode()'s real body run.
        final AuthModeCapturingHandler handler = new AuthModeCapturingHandler();

        final String authMode = handler.getAuthMode();

        assertEquals("mcp.auth.mode", handler.capturedKey, "getAuthMode() must read this exact property key");
        assertEquals(AbstractCacheableHandler.AUTH_MODE_NONE, handler.capturedDefaultValue,
                "getAuthMode() must pass AUTH_MODE_NONE as the default, not a different or re-typed literal");
        assertEquals("none", authMode);
    }

    @Test
    public void testAuthModeNoneConstantMatchesMcpApiManagers() {
        // AbstractCacheableHandler#AUTH_MODE_CONFIG_KEY's Javadoc documents "mcp.auth.mode" as a
        // deliberate duplicate of McpApiManager#getAuthMode()'s literal key (a cross-package
        // import the other way would cycle back into this package's own handlers). Pinning the
        // "none" default the two classes share keeps that duplication from silently drifting
        // apart -- e.g. one side being renamed to "anonymous" while the other stays "none".
        assertEquals(McpApiManager.AUTH_MODE_NONE, AbstractCacheableHandler.AUTH_MODE_NONE);
    }
}
