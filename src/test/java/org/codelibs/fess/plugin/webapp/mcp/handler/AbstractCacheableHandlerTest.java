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
}
